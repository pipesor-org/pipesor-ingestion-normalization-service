package org.pipesor.ingestion.capture.application.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.*;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;

/** Each mutation is one complete local transaction, committed before returning; no remote callbacks. */
public interface CaptureLedger {
    CaptureReceipt accept(Accept command, Instant now);
    CaptureReceipt receipt(UUID id);
    Optional<CaptureReceipt> findDelivery(CaptureScope scope, String source, String deliveryId);
    CaptureReceipt claimPreparation(Claim command, Instant now);
    CaptureReceipt prepare(Prepare command, Instant now);
    default void blockReceipt(UUID id, Instant now) { blockReceipt(id, now, "SCOPE_CHANGED"); }
    /** Terminal stop for an ACCEPTED or PREPARED receipt; reason is SCOPE_CHANGED or SOURCE_EXPIRED. */
    void blockReceipt(UUID id, Instant now, String reason);
    Optional<PublicationOutput> claimOutput(UUID observationId, UUID owner, Instant leaseUntil, Instant now);
    void confirm(PublicationOutput output, Instant now);
    void retry(PublicationOutput output, Instant nextAttemptAt, Instant now);
    void blockOutput(PublicationOutput output, Instant now);
    int purgeConfirmedPayloads(Instant now);
    /** Whether the stored observation recorded a failure (snapshot and evidence) rather than a successful run. */
    boolean observedFailure(java.util.UUID observationId);
}
