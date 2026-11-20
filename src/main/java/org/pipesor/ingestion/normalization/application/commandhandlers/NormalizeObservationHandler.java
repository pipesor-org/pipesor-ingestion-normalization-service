package org.pipesor.ingestion.normalization.application.commandhandlers;

import java.util.Objects;
import java.util.UUID;
import org.pipesor.ingestion.normalization.application.commands.NormalizeObservationCommand;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.LogicalIds;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure.Reason.*;

public final class NormalizeObservationHandler {
    private final EvidenceProtection protection;

    public NormalizeObservationHandler(EvidenceProtection protection) {
        this.protection = Objects.requireNonNull(protection, "Evidence protection required");
    }

    public Result handle(NormalizeObservationCommand command) {
        if (command == null) { throw new NormalizationFailure(INVALID_CONTEXT); }
        var source = command.observation();
        if (!command.expectedCaptureContext().equals(source.captureContext())
                || !command.expectedPipeline().equals(source.pipeline())) { throw new NormalizationFailure(SCOPE_MISMATCH); }
        var failure = source.failure();
        var evidence = source.evidence();
        var input = new EvidenceProtection.Texts(source.execution().branch(), source.service(), source.environment(),
                failure == null ? null : failure.summary(), evidence == null ? null : evidence.job(), evidence == null ? null : evidence.excerpt(),
                source.correlationId(), source.captureContext().scopeRevision());
        java.util.Optional<EvidenceProtection.Texts> decision;
        try { decision = protection.protect(input); }
        catch (RuntimeException unavailable) { throw new NormalizationFailure(PROTECTION_UNAVAILABLE); }
        if (decision == null) { throw new NormalizationFailure(INVALID_PROTECTION_RESULT); }
        var texts = decision.orElseThrow(() -> new NormalizationFailure(PROTECTION_REFUSED));
        if (absent(input.service(), texts.service()) || absent(input.environment(), texts.environment())
                || absent(input.failureSummary(), texts.failureSummary()) || absent(input.job(), texts.job())
                || absent(input.excerpt(), texts.excerpt()) || !input.correlationId().equals(texts.correlationId())
                || !input.scopeRevision().equals(texts.scopeRevision())) { throw new NormalizationFailure(INVALID_PROTECTION_RESULT); }
        WorkflowObservation protectedObservation;
        try {
            var execution = source.execution();
            protectedObservation = new WorkflowObservation(source.captureContext(), source.pipeline(),
                    new WorkflowObservation.Execution(execution.githubRunId(), execution.runAttempt(), texts.branch(),
                            execution.status(), execution.conclusion(), execution.startedAt(), execution.completedAt()),
                    source.commitSha(), texts.service(), texts.environment(), source.correlationId(), source.occurredAt(),
                    source.sourceUpdatedAt(), source.stages(), failure == null ? null
                            : new WorkflowObservation.Failure(failure.stage(), texts.failureSummary(), failure.detectedAt()),
                    evidence == null ? null : new WorkflowObservation.Evidence(evidence.stage(), texts.job(), texts.excerpt(), evidence.sequence(), evidence.sourceLocator()));
        } catch (NormalizationFailure invalid) { throw new NormalizationFailure(INVALID_PROTECTION_RESULT); }
        return new Result(protectedObservation, command.snapshotMessageId(), command.evidenceMessageId());
    }

    private static boolean absent(String input, String output) { return input == null && output != null; }

    /** Only a completed protection call can construct a serializable result. */
    public static final class Result {
        private final WorkflowObservation observation;
        private final UUID snapshotMessageId;
        private final UUID evidenceMessageId;

        private Result(WorkflowObservation observation, UUID snapshotMessageId, UUID evidenceMessageId) {
            this.observation = observation;
            this.snapshotMessageId = snapshotMessageId;
            this.evidenceMessageId = evidenceMessageId;
        }
        public WorkflowObservation observation() { return observation; }
        public UUID snapshotMessageId() { return snapshotMessageId; }
        public UUID evidenceMessageId() { return evidenceMessageId; }
        public UUID pipelineId() { return observation.pipelineId(); }
        public UUID executionId() { return observation.executionId(); }
        public UUID incidentId() { return observation.incidentId(); }
        public UUID evidenceId() { return evidenceMessageId == null ? null : LogicalIds.evidence(evidenceMessageId); }
        @Override public String toString() { return "NormalizationResult[redacted]"; }
    }
}
