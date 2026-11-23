package org.pipesor.ingestion.capture.application.commandhandlers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Claim;
import org.pipesor.ingestion.capture.application.ports.PreparationReadyReceipts;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Explicit bounded pass with caller-owned absolute horizons; no scheduler or implicit recovery policy. */
public final class PreparationWorker {
    public record Result(int attempted, int prepared, int unsupported, int protectionRefused, int unavailable,
            int blocked, int stale, boolean budgetExhausted, boolean interrupted, boolean horizonExpired) {}
    private final PreparationReadyReceipts ready;
    private final PrepareGitHubCapture handler;
    private final Clock clock;
    private final int batchSize;
    private final long budgetNanos;
    private volatile UUID cursor;

    public PreparationWorker(PreparationReadyReceipts ready, PrepareGitHubCapture handler, Clock clock,
            int batchSize, Duration budget) {
        this.ready = Objects.requireNonNull(ready); this.handler = Objects.requireNonNull(handler);
        this.clock = Objects.requireNonNull(clock);
        if (batchSize < 1 || budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Positive preparation batch and budget required");
        }
        this.batchSize = batchSize; this.budgetNanos = budget.toNanos();
    }
    public Result tick(UUID owner, Instant leaseUntil, Instant payloadRecoverUntil) {
        return tick(owner, leaseUntil, payloadRecoverUntil, () -> false);
    }
    public Result tick(UUID owner, Instant leaseUntil, Instant payloadRecoverUntil, BooleanSupplier stopRequested) {
        Objects.requireNonNull(stopRequested);
        var now = clock.instant();
        if (owner == null || leaseUntil == null || payloadRecoverUntil == null
                || !now.isBefore(leaseUntil) || !now.isBefore(payloadRecoverUntil)) { throw new CaptureFailure(INVALID_INPUT); }
        if (stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new Result(0, 0, 0, 0, 0, 0, 0, false, true, false);
        }
        long started = System.nanoTime();
        List<UUID> candidates;
        try {
            var after = cursor;
            candidates = ready.read(clock.instant(), batchSize, after);
            validate(candidates);
            now = clock.instant();
            if (candidates.isEmpty() && after != null && now.isBefore(leaseUntil) && now.isBefore(payloadRecoverUntil)
                    && System.nanoTime() - started < budgetNanos && !stopRequested.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
                candidates = ready.read(now, batchSize, null);
                validate(candidates);
            }
        } catch (RuntimeException unavailable) { throw new CaptureFailure(UNAVAILABLE); }
        int attempted = 0, prepared = 0, unsupported = 0, refused = 0, unavailable = 0, blocked = 0, stale = 0;
        boolean exhausted, interrupted, expired;
        for (int index = 0; ; index++) {
            interrupted = stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted();
            now = clock.instant();
            expired = !now.isBefore(leaseUntil) || !now.isBefore(payloadRecoverUntil);
            exhausted = System.nanoTime() - started >= budgetNanos;
            if (interrupted || expired || exhausted || index == candidates.size()) { break; }
            var receipt = candidates.get(index);
            cursor = receipt; attempted++;
            try {
                handler.prepare(new Claim(receipt, owner, leaseUntil), payloadRecoverUntil);
                prepared++;
            } catch (CaptureFailure failure) {
                switch (failure.reason()) {
                    case INVALID_INPUT -> {
                        now = clock.instant();
                        if (!now.isBefore(leaseUntil) || !now.isBefore(payloadRecoverUntil)) { stale++; }
                        else { unsupported++; }
                    }
                    case SCOPE_CHANGED -> blocked++;
                    case SOURCE_EXPIRED -> unsupported++;
                    case STALE_CLAIM, INVALID_STATE, NOT_FOUND -> stale++;
                    default -> unavailable++;
                }
            } catch (NormalizationFailure failure) {
                switch (failure.reason()) {
                    case PROTECTION_REFUSED -> refused++;
                    case PROTECTION_UNAVAILABLE, INVALID_PROTECTION_RESULT, SERIALIZATION_FAILED -> unavailable++;
                    default -> unsupported++;
                }
            } catch (RuntimeException failure) { unavailable++; }
        }
        return new Result(attempted, prepared, unsupported, refused, unavailable, blocked, stale, exhausted, interrupted, expired);
    }
    private void validate(List<UUID> candidates) {
        if (candidates == null || candidates.size() > batchSize || candidates.stream().anyMatch(Objects::isNull)
                || new HashSet<>(candidates).size() != candidates.size()) { throw new CaptureFailure(UNAVAILABLE); }
    }
}
