package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.pipesor.ingestion.shared.security.SessionTokens;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Human session issuance (platform identity contract v1, §3 and §5): the session signing key
 * ({@code PIPESOR_SESSION_SIGNING_KEY_FILE} or {@code PIPESOR_SESSION_SIGNING_KEY_ID}), the token
 * lifetime and the dashboard origin that receives the one-time handoff.
 */
@ConfigurationProperties("pipesor.session")
public record SessionProperties(Path signingKeyFile, String signingKeyId, Duration lifetime, URI dashboardOrigin) {
    public SessionProperties {
        if ((signingKeyFile == null) == (signingKeyId == null || signingKeyId.isBlank())) {
            throw new IllegalArgumentException("Exactly one of pipesor.session.signing-key-file or pipesor.session.signing-key-id is required");
        }
        lifetime = lifetime == null ? SessionTokens.MAX_LIFETIME : lifetime;
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(SessionTokens.MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("pipesor.session.lifetime must be positive and at most 1h");
        }
        if (dashboardOrigin == null || !dashboardOrigin.isAbsolute() || dashboardOrigin.getHost() == null
                || dashboardOrigin.getUserInfo() != null || dashboardOrigin.getQuery() != null || dashboardOrigin.getFragment() != null
                || !(dashboardOrigin.getRawPath() == null || dashboardOrigin.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("pipesor.session.dashboard-origin must be an origin (scheme://host[:port])");
        }
    }

    @Override public String toString() { return "SessionProperties[configured]"; }
}
