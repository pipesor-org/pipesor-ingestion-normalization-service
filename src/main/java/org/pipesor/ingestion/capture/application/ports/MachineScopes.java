package org.pipesor.ingestion.capture.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;

/** Operator-admitted machine scopes for on-demand (completed-run) captures. */
public interface MachineScopes {
    Optional<CaptureScope> completedScope(String captureDomain, UUID tenant, String repositoryId, String workflowId);
    Instant completedReplayUntil(CaptureScope scope);
}
