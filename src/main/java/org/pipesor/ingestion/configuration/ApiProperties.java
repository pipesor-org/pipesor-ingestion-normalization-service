package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Required environment handoff. None of these values grants GitHub installation admission. */
@ConfigurationProperties("ingestion.api")
public record ApiProperties(Security security, Limits limits) {
    public ApiProperties {
        Objects.requireNonNull(security, "API security configuration required");
        Objects.requireNonNull(limits, "API limits required");
    }

    public record Security(URI issuer, URI jwkSetUri, UUID tenant, String audience, String tokenVersion,
                           String selfScope, String internalScope, Set<UUID> selfCallers, Set<UUID> pipelineCallers,
                           Duration clockSkew) {
        public Security {
            https(issuer); https(jwkSetUri);
            Objects.requireNonNull(tenant, "API tenant required");
            text(audience); text(selfScope); text(internalScope);
            if (!Set.of("1.0", "2.0").contains(tokenVersion)) { throw new IllegalArgumentException("Token version required"); }
            selfCallers = Set.copyOf(Objects.requireNonNull(selfCallers, "Self callers required"));
            pipelineCallers = Set.copyOf(Objects.requireNonNull(pipelineCallers, "Pipeline callers required"));
            if (selfCallers.isEmpty() || pipelineCallers.isEmpty() || !java.util.Collections.disjoint(selfCallers, pipelineCallers)) {
                throw new IllegalArgumentException("Distinct nonempty caller allowlists required");
            }
            Objects.requireNonNull(clockSkew, "Clock skew required");
            if (clockSkew.isNegative()) { throw new IllegalArgumentException("Clock skew must be nonnegative"); }
        }
    }

    public record Limits(Duration requestTimeout, int maxBatchSize, int maxPageSize, int maxRequestBytes,
                         int maxResponseBytes, int maxContextLength) {
        public Limits {
            Objects.requireNonNull(requestTimeout, "Request timeout required");
            if (requestTimeout.isNegative() || requestTimeout.isZero() || maxBatchSize < 1 || maxPageSize < 1
                    || maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE || maxResponseBytes < 1 || maxContextLength < 1) {
                throw new IllegalArgumentException("Positive API limits required");
            }
        }
    }

    private static void https(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("HTTPS identity endpoints required");
        }
    }
    private static void text(String value) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("API identity value required"); }
    }
}
