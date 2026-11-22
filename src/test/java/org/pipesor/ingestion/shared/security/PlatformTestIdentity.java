package org.pipesor.ingestion.shared.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test issuer for platform tokens: a loopback JWKS server publishing the Pipeline workload key and the
 * session key, plus helpers that sign valid or deliberately altered tokens.
 */
public final class PlatformTestIdentity implements AutoCloseable {
    public static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public final KeyPair pipeline = rsa();
    public final KeyPair session = rsa();
    public final KeyPair ingestion = rsa();
    private final HttpServer server;

    public PlatformTestIdentity() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        serve("/pipeline/jwks.json", pipeline);
        serve("/session/jwks.json", session);
        server.start();
    }

    public String pipelineJwks() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/pipeline/jwks.json"; }
    public String sessionJwks() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/session/jwks.json"; }

    /** Spring properties for an Ingestion platform identity trusting Pipeline and sessions (local profile). */
    public List<String> properties(Path directory) {
        var settings = new ArrayList<String>(List.of("pipesor.workload-id=ingestion", "pipesor.tenant-id=" + TENANT,
                "pipesor.signing-key-file=" + pem(directory.resolve("ingestion.pem"), ingestion),
                "pipesor.security.audience=urn:pipesor:api:ingestion",
                "pipesor.security.trusted-workloads[0].id=pipeline",
                "pipesor.security.trusted-workloads[0].jwks-uri=" + pipelineJwks(),
                "pipesor.security.trusted-workloads[0].roles=Pipeline.MachineScope.Check,Pipeline.GitHubFacts.Read",
                "pipesor.security.session.jwks-uri=" + sessionJwks(), "spring.profiles.active=local",
                "management.endpoint.health.group.readiness.include=readinessState"));
        return settings;
    }

    public static Path pem(Path file, KeyPair pair) {
        try {
            Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n", StandardCharsets.US_ASCII);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return file;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    public String workload(String role) { return workload(role, Instant.now().plusSeconds(120), Map.of(), pipeline); }

    public String workload(String role, Instant expiry, Map<String, Object> changes, KeyPair keys) {
        var subject = PlatformIds.workload("pipeline").toString();
        var now = Instant.now().minusSeconds(1);
        var claims = new JWTClaimsSet.Builder().issuer("urn:pipesor:workload:pipeline").audience("urn:pipesor:api:ingestion")
                .subject(subject).claim("oid", subject).claim("azp", subject).claim("tid", TENANT.toString())
                .claim("roles", List.of(role)).claim("idtyp", "app").claim("ver", "2.0")
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString());
        changes.forEach(claims::claim);
        return sign(keys, claims.build());
    }

    public String session(long githubUserId) { return session(githubUserId, Instant.now().plusSeconds(600), Map.of(), session); }

    public String session(long githubUserId, Instant expiry, Map<String, Object> changes, KeyPair keys) {
        var human = PlatformIds.human(githubUserId).toString();
        var now = Instant.now().minusSeconds(1);
        var claims = new JWTClaimsSet.Builder().issuer(PlatformIds.SESSION_ISSUER).audience(SessionTokens.AUDIENCES)
                .subject(human).claim("oid", human).claim("azp", PlatformIds.DASHBOARD_CLIENT.toString())
                .claim("tid", TENANT.toString()).claim("scp", SessionTokens.SCOPES).claim("github_user_id", Long.toString(githubUserId))
                .claim("ver", "2.0").issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(expiry))
                .jwtID(UUID.randomUUID().toString());
        changes.forEach(claims::claim);
        return sign(keys, claims.build());
    }

    public static SigningKey key(KeyPair pair) {
        return new SigningKey() {
            @Override public RSAPublicKey publicKey() { return (RSAPublicKey) pair.getPublic(); }
            @Override public byte[] sign(byte[] input) {
                try {
                    var signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(pair.getPrivate());
                    signature.update(input);
                    return signature.sign();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
    }

    public static KeyPair rsa() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String sign(KeyPair keys, JWTClaimsSet claims) {
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                    .keyID(key(keys).keyId()).build(), claims);
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void serve(String path, KeyPair keys) {
        var body = new JWKSet(new RSAKey.Builder((RSAPublicKey) keys.getPublic()).keyID(key(keys).keyId()).build())
                .toString(true).getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @Override public void close() { server.stop(0); }
}
