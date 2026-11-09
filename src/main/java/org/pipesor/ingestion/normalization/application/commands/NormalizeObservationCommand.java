package org.pipesor.ingestion.normalization.application.commands;

import java.util.UUID;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.LogicalIds;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure.Reason.*;

/** Caller supplies the stable publication intent and independently verified expected machine scope. */
public record NormalizeObservationCommand(WorkflowObservation observation,
        WorkflowObservation.CaptureContext expectedCaptureContext, WorkflowObservation.Pipeline expectedPipeline,
        UUID snapshotMessageId, UUID evidenceMessageId) {
    public NormalizeObservationCommand {
        if (observation == null || expectedCaptureContext == null || expectedPipeline == null) { throw new NormalizationFailure(INVALID_CONTEXT); }
        LogicalIds.required(snapshotMessageId);
        if (observation.evidence() != null) {
            LogicalIds.required(evidenceMessageId);
            if (snapshotMessageId.equals(evidenceMessageId)) { throw new NormalizationFailure(INVALID_IDENTITY); }
        } else if (evidenceMessageId != null) { throw new NormalizationFailure(INVALID_IDENTITY); }
    }
    @Override public String toString() { return "NormalizeObservationCommand[redacted]"; }
}
