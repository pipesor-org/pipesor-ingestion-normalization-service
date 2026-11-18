package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** One explicitly configured caller and capture route; construction supplies no permission. */
@ConfigurationProperties("ingestion.machine-scope")
public record MachineScopeProperties(
        URI issuer, URI jwkSetUri, UUID directoryTenant, String audience,
        UUID callerClient, UUID callerPrincipal, String role, Duration clockSkew,
        UUID captureTenant, String appId, String captureDomain, Path authorityFile,
        int maxFileBytes, int maxEntries, int maxRequestBytes, int maxInFlight, Duration requestTimeout) {
    public MachineScopeProperties {
        https(issuer); https(jwkSetUri);
        for (UUID id : new UUID[] {directoryTenant, callerClient, callerPrincipal, captureTenant}) {
            if (id == null || id.equals(new UUID(0, 0))) throw new IllegalArgumentException("Machine scope identity required");
        }
        if (audience == null || audience.isBlank() || role == null || role.isBlank()) {
            throw new IllegalArgumentException("Machine audience and role required");
        }
        CaptureScope.external(appId); CaptureScope.safe(captureDomain);
        Objects.requireNonNull(authorityFile, "Authority file required");
        if (clockSkew == null || clockSkew.isNegative() || requestTimeout == null
                || requestTimeout.toMillis() < 1 || maxFileBytes < 1 || maxFileBytes == Integer.MAX_VALUE
                || maxEntries < 1 || maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE || maxInFlight < 1) {
            throw new IllegalArgumentException("Explicit machine scope limits required");
        }
        requestTimeout.toNanos(); clockSkew.toNanos();
    }
    private static void https(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("HTTPS identity endpoint required");
        }
    }
    @Override public String toString() { return "MachineScopeProperties[configured]"; }
}
