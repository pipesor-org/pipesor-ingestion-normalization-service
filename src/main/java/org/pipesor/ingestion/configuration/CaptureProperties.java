package org.pipesor.ingestion.configuration;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Capture preparation, protection and publication workers (defaults in application.yml). */
@ConfigurationProperties("pipesor.capture")
public record CaptureProperties(boolean workersEnabled, Archive archive, Protection protection, int maxMessageBytes,
        Preparation preparation, Publication publication) {
    public CaptureProperties {
        Objects.requireNonNull(archive, "pipesor.capture.archive is required");
        Objects.requireNonNull(protection, "pipesor.capture.protection is required");
        Objects.requireNonNull(preparation, "pipesor.capture.preparation is required");
        Objects.requireNonNull(publication, "pipesor.capture.publication is required");
        if (maxMessageBytes < 1) throw new IllegalArgumentException("pipesor.capture.max-message-bytes is required");
    }

    public record Archive(int maxCompressedBytes, int maxExpandedBytes, int maxEntries, int maxStepBytes) { }

    public record Protection(String version, int maxInputBytes, int maxOutputChars, int maxRedactions) {
        public Protection {
            if (version == null || !version.matches("[A-Za-z0-9._:-]{1,128}") || maxInputBytes < 1 || maxOutputChars < 1 || maxRedactions < 1) {
                throw new IllegalArgumentException("Complete pipesor.capture.protection settings are required");
            }
        }
    }

    public record Preparation(int batchSize, Duration budget, Duration interval, Duration lease, Duration recoveryWindow,
            Duration shutdownTimeout) {
        public Preparation { positive(batchSize, budget, interval, lease, recoveryWindow, shutdownTimeout); }
    }

    public record Publication(int batchSize, Duration budget, Duration interval, Duration lease, Duration retryDelay,
            Duration shutdownTimeout) {
        public Publication { positive(batchSize, budget, interval, lease, retryDelay, shutdownTimeout); }
    }

    private static void positive(int batch, Duration... durations) {
        if (batch < 1) throw new IllegalArgumentException("Positive pipesor.capture batch sizes are required");
        for (var duration : durations) {
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("Positive pipesor.capture durations are required");
            }
        }
    }
}
