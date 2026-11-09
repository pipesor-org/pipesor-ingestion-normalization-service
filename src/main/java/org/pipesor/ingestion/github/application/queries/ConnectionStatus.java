package org.pipesor.ingestion.github.application.queries;

import java.time.Instant;
import java.util.UUID;

/** Local metadata only, never evidence of current authorization or safe restoration. */
public record ConnectionStatus(UUID connectionId, long revision, UUID activeGenerationId,
                               Instant createdAt, Instant updatedAt) {}
