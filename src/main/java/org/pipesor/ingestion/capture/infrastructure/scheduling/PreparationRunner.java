package org.pipesor.ingestion.capture.infrastructure.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pipesor.ingestion.capture.application.commandhandlers.PreparationWorker;
import org.springframework.context.SmartLifecycle;

/** Serial local scheduling; durable claims and fences still coordinate different instances. */
public final class PreparationRunner implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(PreparationRunner.class.getName());
    private final PreparationWorker worker;
    private final Clock clock;
    private final Duration interval, lease, recoveryWindow, shutdownTimeout;
    private final AtomicBoolean active = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private AtomicBoolean cancelled;
    private volatile PreparationWorker.Result lastResult;
    private volatile long completedAt;
    private volatile boolean hasCompleted;

    public PreparationRunner(PreparationWorker worker, Clock clock, Duration interval, Duration lease,
            Duration recoveryWindow, Duration shutdownTimeout) {
        this.worker = Objects.requireNonNull(worker); this.clock = Objects.requireNonNull(clock);
        try {
            for (var duration : new Duration[] {interval, lease, recoveryWindow, shutdownTimeout}) {
                if (duration == null || duration.isNegative() || duration.isZero()) { throw new IllegalArgumentException(); }
                duration.toNanos();
            }
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Explicit positive preparation scheduling durations required"); }
        this.interval = interval; this.lease = lease; this.recoveryWindow = recoveryWindow; this.shutdownTimeout = shutdownTimeout;
    }

    @Override public synchronized void start() {
        if (executor != null) { return; }
        var stopRequested = new AtomicBoolean(); cancelled = stopRequested;
        var generation = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "ingestion-preparation"));
        executor = generation;
        future = generation.scheduleWithFixedDelay(() -> {
            if (stopRequested.get() || !active.compareAndSet(false, true)) { return; }
            boolean attempted = false;
            try {
                if (stopRequested.get() || Thread.currentThread().isInterrupted()
                        || hasCompleted && System.nanoTime() - completedAt < interval.toNanos()) { return; }
                attempted = true;
                var started = clock.instant();
                var result = Objects.requireNonNull(worker.tick(UUID.randomUUID(), started.plus(lease), started.plus(recoveryWindow), stopRequested::get));
                lastResult = result;
                boolean attention = result.unsupported() > 0 || result.protectionRefused() > 0 || result.unavailable() > 0
                        || result.blocked() > 0 || result.stale() > 0 || result.budgetExhausted() || result.horizonExpired();
                LOG.log(attention ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG, "Preparation tick outcome: {0}", result);
            } catch (RuntimeException unavailable) {
                lastResult = null;
                LOG.log(System.Logger.Level.WARNING, "Preparation tick unavailable");
            } catch (Error fatal) {
                lastResult = null;
                LOG.log(System.Logger.Level.ERROR, "Preparation runner stopped after fatal failure");
                generation.shutdown();
                throw fatal;
            } finally {
                if (attempted) { completedAt = System.nanoTime(); hasCompleted = true; }
                active.set(false);
            }
        }, 0, interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override public synchronized void stop() {
        if (executor == null) { return; }
        cancelled.set(true);
        var stopping = executor; executor = null; stopping.shutdownNow();
        try {
            if (!stopping.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Preparation shutdown still in flight");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown() && future != null && !future.isDone();
    }
    public PreparationWorker.Result lastResult() { return lastResult; }
}
