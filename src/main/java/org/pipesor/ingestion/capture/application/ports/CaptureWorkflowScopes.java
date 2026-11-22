package org.pipesor.ingestion.capture.application.ports;

import java.time.Instant;
import java.util.UUID;

/** Pipeline-driven workflow capture scopes (ADR-023). */
public interface CaptureWorkflowScopes {
    enum Status { ACTIVE, PAUSED }

    record Sync(UUID tenantId, String repositoryId, String workflowId, UUID pipelineId, Status status, Instant at) { }

    record Synced(String repositoryId, String workflowId, UUID pipelineId, Status status, long revision, String scopeRevision) { }

    /** The repository has no operator admission for the tenant. */
    final class NotAdmitted extends RuntimeException {
        public NotAdmitted() { super("Repository not admitted", null, false, false); }
    }

    /** The workflow scope is already bound to another pipeline. */
    final class PipelineMismatch extends RuntimeException {
        public PipelineMismatch() { super("Workflow scope bound to another pipeline", null, false, false); }
    }

    /** Idempotent: repeating the same status keeps the revision; a status change increments it. */
    Synced sync(Sync request);
}
