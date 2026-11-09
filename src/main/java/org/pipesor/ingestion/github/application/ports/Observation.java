package org.pipesor.ingestion.github.application.ports;

import java.time.Instant;

/** observedAt is assigned for this read with the handler's clock; it is not source updatedAt or a TTL. */
public record Observation<Q, T>(Q request, T value, Instant observedAt) {}
