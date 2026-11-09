package org.pipesor.ingestion.capture.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.*;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;

/** Each mutation is one complete local transaction, committed before returning; no remote callbacks. */
public interface CaptureLedger {
    CaptureReceipt accept(Accept command, Instant now);
    CaptureReceipt receipt(UUID id);
    CaptureReceipt claimPreparation(Claim command, Instant now);
    CaptureReceipt prepare(Prepare command, Instant now);
    void blockReceipt(UUID id, Instant now);
    Optional<PublicationOutput> claimOutput(UUID observationId, UUID owner, Instant leaseUntil, Instant now);
    void confirm(PublicationOutput output, Instant now);
    void retry(PublicationOutput output, Instant nextAttemptAt, Instant now);
    void blockOutput(PublicationOutput output, Instant now);
    int purgeConfirmedPayloads(Instant now);
}
