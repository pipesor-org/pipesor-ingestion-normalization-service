package org.pipesor.ingestion.capture.infrastructure.scheduling;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pipesor.ingestion.capture.application.commandhandlers.PublicationWorker;
import org.springframework.context.SmartLifecycle;

/** Single nonoverlapping scheduler per instance; database fencing coordinates other instances. */
public final class PublicationRunner implements SmartLifecycle {
    private final PublicationWorker worker;
    private final Duration interval, shutdownTimeout;
    private final AtomicBoolean active=new AtomicBoolean();
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private AtomicBoolean cancelled;
    private volatile PublicationWorker.Result lastResult;
    public PublicationRunner(PublicationWorker worker,Duration interval,Duration shutdownTimeout) {
        if(worker==null || interval==null || interval.isNegative() || interval.isZero() || shutdownTimeout==null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) throw new IllegalArgumentException("Explicit publication interval required");
        interval.toNanos();shutdownTimeout.toNanos(); this.worker=worker;this.interval=interval;this.shutdownTimeout=shutdownTimeout;
    }
    @Override public synchronized void start() {
        if(executor!=null) return;
        var stopRequested=new AtomicBoolean();cancelled=stopRequested;
        var generation=Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"ingestion-publication"));
        executor=generation;
        future=generation.scheduleWithFixedDelay(() -> {
            if(!active.compareAndSet(false,true))return;
            try { lastResult=worker.tick(stopRequested::get); }
            catch(RuntimeException unavailable) {
                lastResult=null;
                System.getLogger(PublicationRunner.class.getName()).log(System.Logger.Level.WARNING,"Publication tick unavailable");
            } catch(Error fatal) {
                lastResult=null;
                System.getLogger(PublicationRunner.class.getName()).log(System.Logger.Level.ERROR,"Publication runner stopped after fatal failure");
                generation.shutdown();
                throw fatal;
            } finally { active.set(false); }
        },0,interval.toNanos(),TimeUnit.NANOSECONDS);
    }
    @Override public synchronized void stop() {
        if(executor==null)return;
        cancelled.set(true);
        var stopping=executor;executor=null;stopping.shutdownNow();
        try {
            if(!stopping.awaitTermination(shutdownTimeout.toNanos(),TimeUnit.NANOSECONDS))
                System.getLogger(PublicationRunner.class.getName()).log(System.Logger.Level.WARNING,"Publication shutdown still in flight");
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public synchronized boolean isRunning() { return executor!=null && !executor.isShutdown() && future!=null && !future.isDone(); }
    public PublicationWorker.Result lastResult() { return lastResult; }
}
