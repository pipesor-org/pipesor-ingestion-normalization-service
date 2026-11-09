package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.util.Objects;

/** In-process identity supplied by an authenticated boundary; constructing this record does not authenticate it. */
public record AccessContext(String tenantId, String actorRef, String subjectRef,
                            AccessOperation operation, String correlationId) {
    public AccessContext {
        requireText(tenantId);
        requireText(actorRef);
        requireText(subjectRef);
        requireText(correlationId);
        Objects.requireNonNull(operation, "Operation required");
        if (!actorRef.equals(subjectRef) && !operation.permitsDifferentSubject()) {
            throw new IllegalArgumentException("Operation requires the actor as subject");
        }
    }

    public static void requireText(String value) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Nonblank value required"); }
    }
}
