package org.pipesor.ingestion.capture.application.commands;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler.Result;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

public final class CaptureCommands {
    private CaptureCommands() {}
    /** Authenticated digest and source metadata are supplied by a trusted adapter, never an HTTP body DTO. */
    public record Accept(CaptureScope authenticatedScope, CaptureScope independentlyAuthorizedScope, String source,
            String deliveryId, String authenticatedBodyDigest, String runId, int runAttempt,
            String correlationId, Instant replayUntil) {
        public Accept {
            if (authenticatedScope == null || !authenticatedScope.equals(independentlyAuthorizedScope)) { throw new CaptureFailure(SCOPE_CHANGED); }
            if (!"github_actions".equals(source)) { throw new CaptureFailure(INVALID_INPUT); }
            CaptureScope.safe(deliveryId); CaptureScope.safe(correlationId); CaptureScope.external(runId);
            if (authenticatedBodyDigest == null || !authenticatedBodyDigest.matches("[0-9a-f]{64}")
                    || runAttempt < 1 || replayUntil == null) { throw new CaptureFailure(INVALID_INPUT); }
        }
        @Override public String toString() { return "AcceptCapture[redacted]"; }
    }
    public record Claim(UUID receiptId, UUID owner, Instant leaseUntil) {
        public Claim { if (receiptId == null || owner == null || leaseUntil == null) { throw new CaptureFailure(INVALID_INPUT); } }
    }
    public record Prepare(UUID receiptId, UUID owner, long fence, Result protectedResult,
            String normalizerVersion, String protectionPolicyVersion, String fragmentLocator, Instant payloadRecoverUntil) {
        public Prepare {
            if (receiptId == null || owner == null || fence < 1 || protectedResult == null || payloadRecoverUntil == null) { throw new CaptureFailure(INVALID_INPUT); }
            CaptureScope.safe(normalizerVersion); CaptureScope.safe(protectionPolicyVersion);
            if (protectedResult.observation().evidence() != null) {
                // Explicit trusted source locator; no job/step parsing or stage classification is inferred here.
                var aggregate = protectedResult.observation().evidence().sourceLocator();
                if (fragmentLocator == null || (aggregate != null ? !aggregate.equals(fragmentLocator) : !fragmentLocator.matches("v1:job:[1-9][0-9]*:step:[1-9][0-9]*:index:(0|[1-9][0-9]*)"))) { throw new CaptureFailure(INVALID_INPUT); }
            }
            else if (fragmentLocator != null) { throw new CaptureFailure(INVALID_INPUT); }
        }
        @Override public String toString() { return "PrepareCapture[redacted]"; }
    }
}
