package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, signature verification and file authority with synthetic workload identities. */
class MachineScopeHttpTests {
    private static final KeyPair RSA = keys();
    private static final UUID DIRECTORY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CLIENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PRINCIPAL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TENANT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String ISSUER = "https://identity.example.invalid/v2.0";
    private static final String AUDIENCE = "machine-scope";
    private static final String ROLE = "capture.verify";
    private static final String CORRELATION = "machine-http-test";
    private static final String AUTHORITY = """
        {"formatVersion":"1","revision":"document-r1","humanAdmissions":[],"machineScopes":[
        {"captureDomain":"pilot","tenantId":"44444444-4444-4444-8444-444444444444","appId":"120",
         "accountId":"240","installationId":"360","repositoryId":"480000000000000000001",
         "workflowId":"590000000000000000002","scopeRevision":"machine-r1","allowedActions":["completed"],
         "replayWindow":"PT20M"}]}
        """;
    static ConfigurableApplicationContext context;
    static URI base;
    static Path directory, file;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import(MachineScopeConfiguration.class)
    static class Application {
        @Bean
        @org.springframework.context.annotation.Primary
        MachineScopeConfiguration.MachineDecoder testMachineDecoder(MachineScopeProperties properties) {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) RSA.getPublic()).build();
            decoder.setJwtValidator(MachineScopeConfiguration.validator(properties, Clock.systemUTC()));
            return new MachineScopeConfiguration.MachineDecoder(decoder);
        }
    }

    @BeforeAll static void start() throws Exception {
        directory = Files.createTempDirectory("machine-scope-http");
        file = directory.resolve("authority.json");
        Files.writeString(file, AUTHORITY);
        context = new SpringApplicationBuilder(Application.class).initializers(c ->
                org.springframework.boot.test.util.TestPropertyValues.of(
                    "server.port=0", "server.address=127.0.0.1", "springdoc.api-docs.enabled=false",
                    "ingestion.machine-scope.enabled=true", "ingestion.machine-scope.issuer="+ISSUER,
                    "ingestion.machine-scope.jwk-set-uri=https://identity.example.invalid/keys",
                    "ingestion.machine-scope.directory-tenant="+DIRECTORY, "ingestion.machine-scope.audience="+AUDIENCE,
                    "ingestion.machine-scope.caller-client="+CLIENT, "ingestion.machine-scope.caller-principal="+PRINCIPAL,
                    "ingestion.machine-scope.role="+ROLE, "ingestion.machine-scope.clock-skew=0s",
                    "ingestion.machine-scope.capture-tenant="+TENANT, "ingestion.machine-scope.app-id=120",
                    "ingestion.machine-scope.capture-domain=pilot", "ingestion.machine-scope.authority-file="+file,
                    "ingestion.machine-scope.max-file-bytes=10000", "ingestion.machine-scope.max-entries=20",
                    "ingestion.machine-scope.max-request-bytes=2048", "ingestion.machine-scope.max-in-flight=2",
                    "ingestion.machine-scope.request-timeout=300ms").applyTo(c)).run();
        base = URI.create("http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort());
    }
    @AfterAll static void close() throws Exception { if (context != null) context.close(); Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    @BeforeEach void reset() throws Exception { Files.writeString(file, AUTHORITY); }

    @Test void authorizesExactScopeAndBindsRequestAndCorrelation() throws Exception {
        String body = body();
        var response = post(body, token(Map.of()), CORRELATION);
        assertEquals(200, response.statusCode(), response.body());
        var result = JSON.readTree(response.body());
        assertEquals(JSON.readTree(body).get("requestId").asString(), result.get("requestId").asString());
        assertEquals(CORRELATION, result.get("correlationId").asString());
        assertEquals("AUTHORIZED", result.get("decision").asString());
        assertEquals(Set.of("requestId","correlationId","decision"), result.propertyNames());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void separatesInvalidAuthenticationFromDisallowedCallerAndRole() throws Exception {
        assertEquals(401, post(body(), null, CORRELATION).statusCode());
        for (var changed : List.<Map<String,Object>>of(
                Map.of("aud", "wrong-audience"), Map.of("tid", TENANT.toString()), Map.of("idtyp","user"),
                Map.of("scp","capture.read"), Map.of("ver","1.0"), Map.of("exp",Date.from(Instant.now().minusSeconds(1))))) {
            assertEquals(401, post(body(),token(changed),CORRELATION).statusCode(), changed.toString());
        }
        for (var changed : List.<Map<String,Object>>of(
                Map.of("azp",TENANT.toString()), Map.of("oid",TENANT.toString()), Map.of("roles",List.of("wrong")))) {
            assertEquals(403, post(body(),token(changed),CORRELATION).statusCode(), changed.toString());
        }
    }

    @Test void returnsResourceDenialAndUnavailableWithoutProtectedDetails() throws Exception {
        for (String changed : List.of(body().replace("machine-r1","machine-r0"), body().replace(TENANT.toString(),DIRECTORY.toString()),
                body().replace("\"240\"","\"241\""), body().replace("\"360\"","\"361\""),
                body().replace("480000000000000000001","480000000000000000002"),
                body().replace("590000000000000000002","590000000000000000003"))) {
            var response = post(changed,token(Map.of()),CORRELATION);
            assertEquals(200,response.statusCode(),response.body());
            assertEquals("DENIED",JSON.readTree(response.body()).get("decision").asString());
        }
        Files.writeString(file,"private-invalid-authority");
        var unavailable = post(body(),token(Map.of()),CORRELATION);
        assertEquals(503,unavailable.statusCode(),unavailable.body());
        assertFalse(unavailable.body().contains("private"));
        assertEquals("UNAVAILABLE", JSON.readTree(unavailable.body()).get("code").asString());
    }

    @Test void checksOnlyTheCurrentFileAndDoesNotDetectRevisionReuse() throws Exception {
        var bearer = token(Map.of());
        assertDecision(bearer, "AUTHORIZED");
        Files.writeString(file, AUTHORITY.replace("machine-r1", "machine-r2"));
        assertDecision(bearer, "DENIED");
        Files.writeString(file, AUTHORITY.replace("[\"completed\"]", "[\"in_progress\"]"));
        assertDecision(bearer, "DENIED");
        Files.writeString(file, "{");
        assertEquals(503, post(body(), bearer, CORRELATION).statusCode());
        // Restoring the old label makes its tuple current again; this is no historical guarantee.
        Files.writeString(file, AUTHORITY.replace("document-r1", "document-r2"));
        assertDecision(bearer, "AUTHORIZED");
    }

    private static void assertDecision(String bearer, String decision) throws Exception {
        var response = post(body(), bearer, CORRELATION);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(decision, JSON.readTree(response.body()).get("decision").asString());
    }

    @Test void rejectsBodyMetadataAndExpiryBeforeScopeRead() throws Exception {
        for (var changed : List.of(body()+" {}", body().replace("\"requestId\":", "\"requestId\":\"private\",\"requestId\":"),
                body().replace("machine-r1",""), body().replace("480000000000000000001","0480"))) {
            var bad = post(changed,token(Map.of()),CORRELATION);
            assertEquals(400,bad.statusCode(),bad.body());
            assertFalse(bad.body().contains("private"));
        }
        assertEquals(400,post(body(),token(Map.of()),"other").statusCode());
        assertEquals(413,post("x".repeat(2049),token(Map.of()),CORRELATION).statusCode());
        assertEquals(503,post(body(Instant.now().minusSeconds(1)),token(Map.of()),CORRELATION).statusCode());
    }

    private static String body() throws Exception { return body(Instant.now().plusSeconds(5)); }
    private static String body(Instant deadline) throws Exception {
        return JSON.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"correlationId",CORRELATION,
                "deadlineAt",deadline.toString(),"tenantId",TENANT.toString(),"githubAccountId","240",
                "githubInstallationId","360","githubRepositoryId","480000000000000000001",
                "githubWorkflowId","590000000000000000002","scopeRevision","machine-r1"));
    }
    private static HttpResponse<String> post(String body,String bearer,String correlation) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(MachineScopeConfiguration.PATH)).timeout(Duration.ofSeconds(3))
                .header("Content-Type","application/json").header("X-Correlation-Id",correlation)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        try(var client = HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    private static String token(Map<String,Object> changes) throws Exception {
        var claims = new HashMap<String,Object>();
        claims.put("iss",ISSUER); claims.put("aud",List.of(AUDIENCE)); claims.put("sub",PRINCIPAL.toString());
        claims.put("tid",DIRECTORY.toString()); claims.put("azp",CLIENT.toString()); claims.put("oid",PRINCIPAL.toString());
        claims.put("ver","2.0"); claims.put("idtyp","app"); claims.put("roles",List.of(ROLE));
        claims.put("iat",Date.from(Instant.now().minusSeconds(1))); claims.put("exp",Date.from(Instant.now().plusSeconds(60)));
        claims.putAll(changes);
        var builder = new JWTClaimsSet.Builder(); claims.forEach(builder::claim);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),builder.build());
        jwt.sign(new RSASSASigner(RSA.getPrivate()));
        return jwt.serialize();
    }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
}
