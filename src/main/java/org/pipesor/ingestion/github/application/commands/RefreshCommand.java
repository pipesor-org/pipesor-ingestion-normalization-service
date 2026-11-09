package org.pipesor.ingestion.github.application.commands;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;

/** Private one-shot execution; callers must satisfy restore/admission operating gates. */
public record RefreshCommand(UUID connectionId, ConnectionIdentity identity, UUID generationId,
        long connectionRevision, long generationRevision, UUID operationId, UUID ownerId, Instant deadlineAt) {
    public RefreshCommand {
        Objects.requireNonNull(connectionId); Objects.requireNonNull(identity); Objects.requireNonNull(generationId);
        Objects.requireNonNull(operationId); Objects.requireNonNull(ownerId); Objects.requireNonNull(deadlineAt);
        // Floor before persistence so PostgreSQL precision cannot extend the execution budget.
        deadlineAt = deadlineAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (connectionRevision < 0 || generationRevision < 0) { throw new IllegalArgumentException("Invalid refresh revision"); }
    }
}
