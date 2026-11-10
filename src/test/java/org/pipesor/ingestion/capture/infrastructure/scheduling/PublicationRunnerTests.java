package org.pipesor.ingestion.capture.infrastructure.scheduling;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.pipesor.ingestion.capture.application.commandhandlers.PublicationWorker;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PublicationRunnerTests {
    @Test void restartNeverOverlapsAnUninterruptibleTickAndStopWaitsOnlyItsExplicitBudget() throws Exception {
        var worker=mock(PublicationWorker.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var active=new AtomicInteger();var maximum=new AtomicInteger();var calls=new AtomicInteger();
        when(worker.tick(any(java.util.function.BooleanSupplier.class))).thenAnswer(inv -> {
            int n=active.incrementAndGet();maximum.accumulateAndGet(n,Math::max);calls.incrementAndGet();entered.countDown();
            while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            active.decrementAndGet();return new PublicationWorker.Result(0,0,0,0,0,0,false,false);
        });
        var runner=new PublicationRunner(worker,Duration.ofMillis(10),Duration.ofMillis(20));
        try {
            runner.start();assertTrue(entered.await(3,TimeUnit.SECONDS));runner.stop();assertFalse(runner.isRunning());runner.start();
            Thread.sleep(70);assertEquals(1,calls.get());assertEquals(1,maximum.get());
            release.countDown();
        } finally {release.countDown();runner.stop();}
    }
    @Test void fatalFailureIsReportedInactiveWithoutLeakingItsPrivateCause(CapturedOutput output) throws Exception {
        var worker=mock(PublicationWorker.class);var fatalThread=new java.util.concurrent.atomic.AtomicReference<Thread>();
        when(worker.tick(any(java.util.function.BooleanSupplier.class))).thenAnswer(inv->{fatalThread.set(Thread.currentThread());throw new AssertionError("private-fatal-secret");});
        var runner=new PublicationRunner(worker,Duration.ofMillis(10),Duration.ofSeconds(1));
        try {
            runner.start();
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(runner.isRunning())Thread.sleep(10);});
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(fatalThread.get()==null || fatalThread.get().isAlive())Thread.sleep(10);});
            assertFalse(output.getAll().contains("private-fatal-secret"));
            verify(worker,times(1)).tick(any(java.util.function.BooleanSupplier.class));assertFalse(runner.isRunning());assertNull(runner.lastResult());
        } finally {runner.stop();}
    }
    @Test void schedulerSurvivesAFailedTickWithoutExposingPrivateDiagnostics(CapturedOutput output) throws Exception {
        var worker=mock(PublicationWorker.class);var completed=new CountDownLatch(1);var result=new PublicationWorker.Result(1,1,0,0,0,0,false,false);
        when(worker.tick(any(java.util.function.BooleanSupplier.class))).thenThrow(new IllegalStateException("private-secret")).thenAnswer(inv->{completed.countDown();return result;});
        var runner=new PublicationRunner(worker,Duration.ofMillis(10),Duration.ofSeconds(1));
        try(var logs=new Logs()) {
            runner.start();assertTrue(completed.await(3,TimeUnit.SECONDS));assertTrue(runner.isRunning());
            assertTrue(logs.records.stream().anyMatch(r->r.getMessage().equals("Publication tick unavailable")));
            assertTrue(logs.records.stream().allMatch(r->r.getThrown()==null && !r.getMessage().contains("private-secret")));
            assertFalse(output.getAll().contains("private-secret"));
        }finally{runner.stop();}
    }
    private static final class Logs implements AutoCloseable {
        final java.util.List<java.util.logging.LogRecord> records=new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.logging.Logger logger=java.util.logging.Logger.getLogger(PublicationRunner.class.getName());
        final java.util.logging.Level level=logger.getLevel();
        final boolean parents=logger.getUseParentHandlers();
        final java.util.logging.Handler handler=new java.util.logging.Handler(){
            @Override public void publish(java.util.logging.LogRecord record){records.add(record);}
            @Override public void flush(){}
            @Override public void close(){}
        };
        Logs(){logger.setUseParentHandlers(false);logger.setLevel(java.util.logging.Level.ALL);logger.addHandler(handler);}
        @Override public void close(){logger.removeHandler(handler);logger.setUseParentHandlers(parents);logger.setLevel(level);}
    }

}
