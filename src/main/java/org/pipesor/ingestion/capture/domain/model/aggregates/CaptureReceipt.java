package org.pipesor.ingestion.capture.domain.model.aggregates;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.pipesor.ingestion.shared.domain.model.Entity;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Root of the atomic receipt/observation/closed publication plan, with immutable source identity. */
public final class CaptureReceipt extends Entity {
    private final CaptureScope scope;
    private final String runId;
    private final int runAttempt;
    private final String correlationId;
    private final UUID snapshotMessageId;
    private final UUID evidenceMessageId;
    private final String phase;
    private final UUID observationId;
    private final UUID claimOwner;
    private final long claimFence;
    private final Instant leaseUntil;
    private final String blockedReason;
    public CaptureReceipt(UUID id, CaptureScope scope, String runId, int runAttempt, String correlationId,
            UUID snapshotMessageId, UUID evidenceMessageId, String phase, UUID observationId,
            UUID claimOwner, long claimFence, Instant leaseUntil) {
        this(id, scope, runId, runAttempt, correlationId, snapshotMessageId, evidenceMessageId, phase, observationId,
                claimOwner, claimFence, leaseUntil, null);
    }
    public CaptureReceipt(UUID id, CaptureScope scope, String runId, int runAttempt, String correlationId,
            UUID snapshotMessageId, UUID evidenceMessageId, String phase, UUID observationId,
            UUID claimOwner, long claimFence, Instant leaseUntil, String blockedReason) {
        super(id); this.scope = scope; this.runId = runId; this.runAttempt = runAttempt;
        this.correlationId = correlationId; this.snapshotMessageId = snapshotMessageId;
        this.evidenceMessageId = evidenceMessageId; this.phase = phase; this.observationId = observationId;
        this.claimOwner = claimOwner; this.claimFence = claimFence; this.leaseUntil = leaseUntil;
        this.blockedReason = blockedReason;
    }
    public void requireClaim(UUID owner, long fence, Instant now) {
        if (!"ACCEPTED".equals(phase) || !owner.equals(claimOwner) || fence != claimFence
                || leaseUntil == null || !now.isBefore(leaseUntil)) { throw new CaptureFailure(STALE_CLAIM); }
    }
    public void requirePreparedObservation(WorkflowObservation observation, UUID snapshotId, UUID evidenceId) {
        if (!scope.context().equals(observation.captureContext()) || !scope.pipeline().equals(observation.pipeline())
                || !runId.equals(observation.execution().githubRunId()) || runAttempt != observation.execution().runAttempt()
                || !correlationId.equals(observation.correlationId()) || !snapshotMessageId.equals(snapshotId)
                || (observation.evidence() != null && !evidenceMessageId.equals(evidenceId))) {
            throw new CaptureFailure(CONFLICT);
        }
    }
    public CaptureScope scope() { return scope; }
    /** Why a BLOCKED receipt stopped: SCOPE_CHANGED or SOURCE_EXPIRED; null in every other phase. */
    public String blockedReason() { return blockedReason; }
    public String runId() { return runId; }
    public int runAttempt() { return runAttempt; }
    public String correlationId() { return correlationId; }
    public UUID snapshotMessageId() { return snapshotMessageId; }
    public UUID evidenceMessageId() { return evidenceMessageId; }
    public String phase() { return phase; }
    public UUID observationId() { return observationId; }
    public UUID claimOwner() { return claimOwner; }
    public long claimFence() { return claimFence; }
    public Instant leaseUntil() { return leaseUntil; }
    @Override public String toString() { return "CaptureReceipt[redacted]"; }
}
