package org.pipesor.ingestion.capture.application.commandhandlers;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.ports.PublicationReadyReceipts;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;

/** A bounded pass; the time budget stops new starts, not an already running provider call. */
public final class PublicationWorker {
    public record Result(int attempted, int published, int unclaimed, int unavailable, int blocked, int stale, boolean budgetExhausted, boolean interrupted) {}
    private final PublicationReadyReceipts ready;
    private final CaptureHandler handler;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease, retryDelay, budget;
    private volatile UUID cursor;
    public PublicationWorker(PublicationReadyReceipts ready, CaptureHandler handler, Clock clock,
            int batchSize, Duration lease, Duration retryDelay, Duration budget) {
        this.ready=Objects.requireNonNull(ready); this.handler=Objects.requireNonNull(handler); this.clock=Objects.requireNonNull(clock);
        if(batchSize<1) throw new IllegalArgumentException("Positive publication batch required");
        for(var duration:new Duration[]{lease,retryDelay,budget}) {
            if(duration==null || duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Positive publication durations required");
            duration.toNanos();
        }
        this.batchSize=batchSize;this.lease=lease;this.retryDelay=retryDelay;this.budget=budget;
    }
    public Result tick() { return tick(()->false); }
    public Result tick(java.util.function.BooleanSupplier stopRequested) {
        Objects.requireNonNull(stopRequested);
        if(stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted())return new Result(0,0,0,0,0,0,false,true);
        long started=System.nanoTime();
        java.util.List<UUID> candidates;
        try {
            var after=cursor;
            candidates=ready.read(clock.instant(),batchSize,after);
            if(candidates!=null && candidates.isEmpty() && after!=null) candidates=ready.read(clock.instant(),batchSize,null);
            if(candidates==null || candidates.size()>batchSize || candidates.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(candidates).size()!=candidates.size()) throw new IllegalStateException();
        } catch(RuntimeException unavailable) { throw new CaptureFailure(CaptureFailure.Reason.UNAVAILABLE); }
        int attempted=0,published=0,unclaimed=0,unavailable=0,blocked=0,stale=0;
        boolean exhausted=false,interrupted=false;
        for(var receipt:candidates) {
            if(stopRequested.getAsBoolean() || Thread.currentThread().isInterrupted()) { interrupted=true;break; }
            if(System.nanoTime()-started>=budget.toNanos()) { exhausted=true;break; }
            cursor=receipt;
            attempted++;
            try {
                var now=clock.instant();
                if(handler.publishNext(receipt,UUID.randomUUID(),now.plus(lease),now.plus(retryDelay))) published++;
                else unclaimed++;
            } catch(CaptureFailure failure) {
                switch(failure.reason()) {
                    case SCOPE_CHANGED -> blocked++;
                    case STALE_CLAIM, INVALID_STATE -> stale++;
                    default -> unavailable++;
                }
            } catch(RuntimeException failure) { unavailable++; }
        }
        return new Result(attempted,published,unclaimed,unavailable,blocked,stale,exhausted,interrupted);
    }
}
