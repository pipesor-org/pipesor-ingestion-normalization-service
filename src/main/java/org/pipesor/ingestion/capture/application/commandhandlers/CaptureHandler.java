package org.pipesor.ingestion.capture.application.commandhandlers;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.*;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Explicit local operations only: no scheduler, webhook verification or implicit runtime policy. */
public final class CaptureHandler {
    private final CaptureLedger ledger;
    private final CurrentMachineScope scope;
    private final BrokerPublisher publisher;
    private final Clock clock;
    public CaptureHandler(CaptureLedger ledger, CurrentMachineScope scope, BrokerPublisher publisher, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.scope = Objects.requireNonNull(scope); this.publisher = Objects.requireNonNull(publisher); this.clock = Objects.requireNonNull(clock);
    }
    public CaptureReceipt accept(Accept command) {
        if (command == null) { throw new CaptureFailure(INVALID_INPUT); }
        verify(command.independentlyAuthorizedScope());
        return ledger.accept(command, clock.instant());
    }
    public Optional<CaptureReceipt> findDelivery(CaptureScope expected, String source, String deliveryId) {
        verify(expected);
        return ledger.findDelivery(expected, source, deliveryId);
    }
    /** Whether the receipt's prepared observation recorded a failure; false before preparation. */
    public boolean observedFailure(CaptureReceipt receipt) {
        return receipt.observationId() != null && ledger.observedFailure(receipt.observationId());
    }
    /** Commit this claim before reading protected upstream data; the returned IDs survive every retry. */
    public CaptureReceipt claimPreparation(Claim command) {
        if (command == null) { throw new CaptureFailure(INVALID_INPUT); }
        var receipt = ledger.receipt(command.receiptId());
        checkReceiptScope(receipt);
        return ledger.claimPreparation(command, clock.instant());
    }
    public CaptureReceipt prepare(Prepare command) {
        if (command == null) { throw new CaptureFailure(INVALID_INPUT); }
        var receipt = ledger.receipt(command.receiptId());
        checkReceiptScope(receipt);
        return ledger.prepare(command, clock.instant());
    }
    /** One broker attempt; all scope and broker I/O occurs outside the ledger's SQL transactions. */
    public boolean publishNext(UUID receiptId, UUID owner, Instant leaseUntil, Instant nextAttemptAt) {
        if (receiptId == null || owner == null || leaseUntil == null || nextAttemptAt == null
                || nextAttemptAt.isBefore(clock.instant())) { throw new CaptureFailure(INVALID_INPUT); }
        var receipt = ledger.receipt(receiptId);
        checkReceiptScope(receipt);
        if (!"PREPARED".equals(receipt.phase())) {
            if ("PUBLISHED".equals(receipt.phase())) { return false; }
            throw new CaptureFailure(INVALID_STATE);
        }
        var next = ledger.claimOutput(receipt.observationId(), owner, leaseUntil, clock.instant());
        if (next.isEmpty()) { return false; }
        var output = next.orElseThrow();
        try { verify(output.scope()); }
        catch (CaptureFailure failure) {
            if (failure.reason() == SCOPE_CHANGED) {
                try { ledger.blockOutput(output, clock.instant()); }
                catch (CaptureFailure blockFailure) { if (blockFailure.reason() != STALE_CLAIM) { throw blockFailure; } }
            } else { retry(output, nextAttemptAt); }
            throw failure;
        }
        try { publisher.publish(output); }
        catch (RuntimeException unavailable) {
            retry(output, nextAttemptAt);
            throw new CaptureFailure(UNAVAILABLE);
        }
        ledger.confirm(output, clock.instant());
        return true;
    }
    public int purgeConfirmedPayloads() { return ledger.purgeConfirmedPayloads(clock.instant()); }
    private void retry(PublicationOutput output, Instant lowerBound) {
        var now = clock.instant();
        try { ledger.retry(output, lowerBound.isBefore(now) ? now : lowerBound, now); }
        catch (CaptureFailure failure) { if (failure.reason() != STALE_CLAIM) { throw failure; } }
    }
    private void checkReceiptScope(CaptureReceipt receipt) {
        try { verify(receipt.scope()); }
        catch (CaptureFailure failure) {
            if (failure.reason() == SCOPE_CHANGED) {
                try { ledger.blockReceipt(receipt.id(), clock.instant()); }
                catch (CaptureFailure blockFailure) { if (blockFailure.reason() != STALE_CLAIM) { throw blockFailure; } }
            }
            throw failure;
        }
    }
    private void verify(CaptureScope expected) {
        CurrentMachineScope.Decision decision;
        try { decision = scope.verify(expected); }
        catch (RuntimeException unavailable) { throw new CaptureFailure(UNAVAILABLE); }
        if (decision == CurrentMachineScope.Decision.CHANGED_OR_LOST) { throw new CaptureFailure(SCOPE_CHANGED); }
        if (decision != CurrentMachineScope.Decision.AUTHORIZED) { throw new CaptureFailure(UNAVAILABLE); }
    }
}
