package org.pipesor.ingestion.configuration;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounds of the human and internal HTTP APIs (defaults in application.yml). */
@ConfigurationProperties("pipesor.api")
public record ApiProperties(Limits limits) {
    public ApiProperties {
        Objects.requireNonNull(limits, "pipesor.api.limits is required");
    }

    public record Limits(Duration requestTimeout, int maxBatchSize, int maxPageSize, int maxRequestBytes,
                         int maxResponseBytes, int maxContextLength) {
        public Limits {
            Objects.requireNonNull(requestTimeout, "pipesor.api.limits.request-timeout is required");
            if (requestTimeout.isNegative() || requestTimeout.isZero() || maxBatchSize < 1 || maxPageSize < 1
                    || maxRequestBytes < 1 || maxRequestBytes == Integer.MAX_VALUE || maxResponseBytes < 1 || maxContextLength < 1) {
                throw new IllegalArgumentException("Positive pipesor.api.limits are required");
            }
        }
    }
}
