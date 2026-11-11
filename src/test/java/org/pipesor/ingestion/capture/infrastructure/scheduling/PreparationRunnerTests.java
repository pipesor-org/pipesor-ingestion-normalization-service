package org.pipesor.ingestion.capture.infrastructure.scheduling;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pipesor.ingestion.capture.application.commandhandlers.PreparationWorker;
import org.springframework.boot.test.system.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PreparationRunnerTests {
    private static final Instant NOW = Instant.parse("2026-11-11T18:00:00Z");
    private static final PreparationWorker.Result HEALTHY = new PreparationWorker.Result(2, 2, 0, 0, 0, 0, 0, false, false, false);
    private static PreparationRunner runner(PreparationWorker worker, Clock clock) {
        return new PreparationRunner(worker, clock, Duration.ofMillis(10), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofMillis(30));
    }
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> { while (!condition.getAsBoolean()) { Thread.sleep(1); } });
    }
    @Test void derivesEachBatchFromOneClockSampleAndReservesAnotherOwnerForNextTick() throws Exception {
        var worker = mock(PreparationWorker.class); var samples = new AtomicInteger();
        var calls = new CopyOnWriteArrayList<List<Object>>(); var second = new CountDownLatch(1);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return NOW.plusSeconds(samples.getAndIncrement() * 17L); }
        };
        when(worker.tick(any(UUID.class), any(Instant.class), any(Instant.class), any(BooleanSupplier.class))).thenAnswer(inv -> {
            calls.add(List.of(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
            if (calls.size() == 2) {
                second.countDown();
                BooleanSupplier stop = inv.getArgument(3);
                while (!stop.getAsBoolean()) { java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); }
            }
            return HEALTHY;
        });
        var runner = runner(worker, clock);
        try { runner.start(); assertTrue(second.await(3, TimeUnit.SECONDS)); }
        finally { runner.stop(); }
        assertEquals(2, samples.get()); assertEquals(2, calls.size());
        assertNotEquals(calls.get(0).get(0), calls.get(1).get(0));
        for (int i = 0; i < 2; i++) {
            assertEquals(NOW.plusSeconds(i * 17L + 30), calls.get(i).get(1));
            assertEquals(NOW.plusSeconds(i * 17L + 7200), calls.get(i).get(2));
        }
    }
    @Test void restartCannotOverlapAnOldTickAndItsStopTokenSurvivesClearedInterrupts() throws Exception {
        var worker = mock(PreparationWorker.class); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var active = new AtomicInteger(); var maximum = new AtomicInteger();
        var oldStop = new AtomicReference<BooleanSupplier>();
        var firstFinished = new AtomicLong(); var nextStarted = new AtomicLong();
        when(worker.tick(any(UUID.class), any(Instant.class), any(Instant.class), any(BooleanSupplier.class))).thenAnswer(inv -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            if (calls.incrementAndGet() == 1) {
                oldStop.set(inv.getArgument(3)); entered.countDown();
                while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { Thread.interrupted(); } }
                firstFinished.set(System.nanoTime());
            } else { nextStarted.compareAndSet(0, System.nanoTime()); }
            active.decrementAndGet(); return HEALTHY;
        });
        var runner = new PreparationRunner(worker, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(200), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofMillis(30));
        try {
            runner.start(); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> { runner.stop(); });
            assertFalse(runner.isRunning()); assertTrue(oldStop.get().getAsBoolean());
            runner.start(); Thread.sleep(70); assertEquals(1, calls.get()); assertEquals(1, maximum.get());
            release.countDown(); await(() -> nextStarted.get() != 0); assertEquals(1, maximum.get());
            assertTrue(nextStarted.get() - firstFinished.get() >= Duration.ofMillis(200).toNanos(), "Restart must preserve delay after the old tick finishes");
        } finally { release.countDown(); runner.stop(); }
    }
    @Test void unavailableTickIsNotAnEmptySuccessAndRefusalCountsRemainObservable(CapturedOutput output) throws Exception {
        var worker = mock(PreparationWorker.class); var calls = new AtomicInteger();
        var retryEntered = new CountDownLatch(1); var releaseRetry = new CountDownLatch(1);
        var refused = new PreparationWorker.Result(1, 0, 1, 0, 0, 0, 0, false, false, false);
        when(worker.tick(any(UUID.class), any(Instant.class), any(Instant.class), any(BooleanSupplier.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("private-provider-secret"); }
            retryEntered.countDown(); releaseRetry.await(3, TimeUnit.SECONDS);
            return refused;
        });
        var runner = runner(worker, Clock.fixed(NOW, ZoneOffset.UTC));
        try (var logs = new Logs()) {
            runner.start(); assertTrue(retryEntered.await(3, TimeUnit.SECONDS)); assertNull(runner.lastResult()); releaseRetry.countDown();
            await(() -> logs.records.stream().anyMatch(r -> r.getMessage().equals("Preparation tick outcome: {0}")));
            assertEquals(refused, runner.lastResult());
            assertTrue(runner.isRunning());
            assertTrue(logs.records.stream().anyMatch(r -> r.getMessage().equals("Preparation tick unavailable")));
            assertTrue(logs.records.stream().anyMatch(r -> r.getMessage().equals("Preparation tick outcome: {0}")
                    && r.getParameters() != null && Arrays.asList(r.getParameters()).contains(refused)
                    && r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()));
            assertTrue(logs.records.stream().allMatch(r -> r.getThrown() == null));
            assertFalse(output.getAll().contains("private-provider-secret"));
        } finally { releaseRetry.countDown(); runner.stop(); }
    }
    @Test void fatalFailureStopsTheSchedulerWithoutLeakingTheCause(CapturedOutput output) {
        var worker = mock(PreparationWorker.class); var fatalThread = new AtomicReference<Thread>();
        when(worker.tick(any(UUID.class), any(Instant.class), any(Instant.class), any(BooleanSupplier.class))).thenAnswer(inv -> {
            fatalThread.set(Thread.currentThread()); throw new AssertionError("private-fatal-material");
        });
        var runner = runner(worker, Clock.fixed(NOW, ZoneOffset.UTC));
        try {
            runner.start(); await(() -> !runner.isRunning());
            await(() -> fatalThread.get() != null && !fatalThread.get().isAlive());
            assertNull(runner.lastResult()); assertFalse(output.getAll().contains("private-fatal-material"));
            verify(worker, times(1)).tick(any(UUID.class), any(Instant.class), any(Instant.class), any(BooleanSupplier.class));
        } finally { runner.stop(); }
    }
    private static final class Logs implements AutoCloseable {
        final List<java.util.logging.LogRecord> records = new CopyOnWriteArrayList<>();
        final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(PreparationRunner.class.getName());
        final java.util.logging.Level level = logger.getLevel();
        final boolean parents = logger.getUseParentHandlers();
        final java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logs() { logger.setUseParentHandlers(false); logger.setLevel(java.util.logging.Level.ALL); logger.addHandler(handler); }
        @Override public void close() { logger.removeHandler(handler); logger.setUseParentHandlers(parents); logger.setLevel(level); }
    }
}
