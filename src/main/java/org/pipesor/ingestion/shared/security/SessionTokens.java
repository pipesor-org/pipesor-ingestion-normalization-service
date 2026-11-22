package org.pipesor.ingestion.shared.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Issues human session tokens exactly as the platform identity contract v1, §3, defines them. */
public final class SessionTokens {
    public static final Duration MAX_LIFETIME = Duration.ofHours(1);
    public static final List<String> AUDIENCES = List.of(PlatformIds.audience("ingestion"),
            PlatformIds.audience("pipeline"), PlatformIds.audience("log-event"), PlatformIds.audience("recommendation"));
    public static final String SCOPES = "GitHub.Self Pipeline.Access Evidence.Read Diagnosis.Read Diagnosis.Review";

    public record Token(String value, Instant expiresAt) {
        @Override public String toString() { return "SessionToken[redacted]"; }
    }

    private final UUID tenant;
    private final SigningKey key;
    private final Duration lifetime;
    private final Clock clock;

    public SessionTokens(UUID tenant, SigningKey key, Duration lifetime, Clock clock) {
        this.tenant = Objects.requireNonNull(tenant);
        this.key = Objects.requireNonNull(key);
        this.clock = Objects.requireNonNull(clock);
        if (lifetime == null || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("Session lifetime must be positive and at most one hour");
        }
        this.lifetime = lifetime;
    }

    public Token issue(long githubUserId) {
        String human = PlatformIds.human(githubUserId).toString();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expires = now.plus(lifetime);
        var claims = new JWTClaimsSet.Builder()
                .issuer(PlatformIds.SESSION_ISSUER).audience(AUDIENCES).claim("tid", tenant.toString())
                .subject(human).claim("oid", human).claim("azp", PlatformIds.DASHBOARD_CLIENT.toString())
                .claim("scp", SCOPES).claim("github_user_id", Long.toString(githubUserId)).claim("ver", "2.0")
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(expires))
                .jwtID(UUID.randomUUID().toString()).build();
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.keyId()).build();
        String signingInput = header.toBase64URL() + "." + Base64URL.encode(claims.toString().getBytes(StandardCharsets.UTF_8));
        String signature = Base64URL.encode(key.sign(signingInput.getBytes(StandardCharsets.US_ASCII))).toString();
        return new Token(signingInput + "." + signature, expires);
    }
}
