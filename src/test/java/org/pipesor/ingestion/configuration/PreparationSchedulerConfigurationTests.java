package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.commandhandlers.PreparationWorker;
import org.pipesor.ingestion.capture.application.commandhandlers.PrepareGitHubCapture;
import org.pipesor.ingestion.capture.infrastructure.scheduling.PreparationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparationSchedulerConfigurationTests {
    private static final List<String> SETTINGS = List.of("ingestion.preparation-scheduler.enabled=true",
            "ingestion.preparation-scheduler.interval=1h", "ingestion.preparation-scheduler.lease=1m",
            "ingestion.preparation-scheduler.recovery-window=5m", "ingestion.preparation-scheduler.shutdown-timeout=1s");
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PreparationSchedulerConfiguration.class);
    private ApplicationContextRunner dependencies() {
        var worker = mock(PreparationWorker.class);
        when(worker.tick(any(), any(), any(), any())).thenReturn(new PreparationWorker.Result(0, 0, 0, 0, 0, 0, 0, false, false, false));
        return runner.withBean(PreparationWorker.class, () -> worker).withBean(Clock.class, Clock::systemUTC);
    }
    @Test void oldExplicitWorkerFlagNeverStartsSchedulerAndDefaultNeedsNoDependencies() {
        runner.run(context -> { assertNull(context.getStartupFailure()); assertTrue(context.getBeansOfType(PreparationRunner.class).isEmpty()); });
        runner.withUserConfiguration(PreparationWorkerConfiguration.class)
                .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(Clock.class, Clock::systemUTC).withBean(PrepareGitHubCapture.class, () -> mock(PrepareGitHubCapture.class))
                .withPropertyValues("ingestion.preparation-worker.enabled=true", "ingestion.preparation-worker.batch-size=2", "ingestion.preparation-worker.budget=1s")
                .run(context -> {
                    assertNull(context.getStartupFailure()); assertNotNull(context.getBean(PreparationWorker.class));
                    assertTrue(context.getBeansOfType(PreparationRunner.class).isEmpty()); verifyNoInteractions(context.getBean(PrepareGitHubCapture.class));
                });
    }
    @Test void explicitSchedulerConfigurationStartsManagedLifecycle() {
        dependencies().withPropertyValues(SETTINGS.toArray(String[]::new)).run(context -> {
            assertNull(context.getStartupFailure()); assertTrue(context.getBean(PreparationRunner.class).isRunning());
        });
    }
    @Test void requiredAndInvalidDurationsFailWithFixedDiagnosticAndNoRejectedValues() {
        for (var key : List.of("interval", "lease", "recovery-window", "shutdown-timeout")) {
            var values = new ArrayList<>(SETTINGS); values.removeIf(v -> v.startsWith("ingestion.preparation-scheduler." + key + "="));
            dependencies().withPropertyValues(values.toArray(String[]::new)).run(context -> assertConfigurationFailure(context.getStartupFailure(), key));
            for (var invalid : List.of("0s", "-1s", "private-invalid", "999999999999999999999999999999999s")) {
                dependencies().withPropertyValues(SETTINGS.toArray(String[]::new))
                        .withPropertyValues("ingestion.preparation-scheduler." + key + "=" + invalid)
                        .run(context -> assertConfigurationFailure(context.getStartupFailure(), key));
            }
        }
    }
    @Test void schedulerWithoutActualWorkerFailsInsteadOfSupplyingFallback() {
        runner.withBean(Clock.class, Clock::systemUTC).withPropertyValues(SETTINGS.toArray(String[]::new)).run(context -> {
            var failure = context.getStartupFailure(); assertNotNull(failure);
            while (failure.getCause() != null) { failure = failure.getCause(); }
            assertEquals(PreparationWorker.class, assertInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class, failure).getBeanType());
        });
    }
    private static void assertConfigurationFailure(Throwable failure, String key) {
        assertNotNull(failure); assertFalse(failure.toString().contains("private-invalid"));
        while (failure.getCause() != null) { failure = failure.getCause(); }
        assertEquals(IllegalArgumentException.class, failure.getClass());
        assertEquals("Invalid or missing preparation scheduler setting: " + key, failure.getMessage());
    }
}
