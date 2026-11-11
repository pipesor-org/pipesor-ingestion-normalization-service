package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.commandhandlers.PreparationWorker;
import org.pipesor.ingestion.capture.application.commandhandlers.PrepareGitHubCapture;
import org.pipesor.ingestion.capture.application.ports.PreparationReadyReceipts;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparationWorkerConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PreparationWorkerConfiguration.class);
    private ApplicationContextRunner dependencies() {
        return runner.withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(Clock.class, Clock::systemUTC);
    }
    private ApplicationContextRunner enabled() {
        return dependencies().withBean(PrepareGitHubCapture.class, () -> mock(PrepareGitHubCapture.class))
                .withPropertyValues("ingestion.preparation-worker.enabled=true", "ingestion.preparation-worker.batch-size=3", "ingestion.preparation-worker.budget=2s");
    }
    @Test void disabledRequiresNoPortsOrSettings() {
        runner.run(context -> { assertNull(context.getStartupFailure()); assertTrue(context.getBeansOfType(PreparationWorker.class).isEmpty()); });
    }
    @Test void enabledComposesSelectorAndWorkerWithoutStartingSourceReads() {
        enabled().run(context -> {
            assertNull(context.getStartupFailure()); assertNotNull(context.getBean(PreparationWorker.class));
            assertNotNull(context.getBean(PreparationReadyReceipts.class)); verifyNoInteractions(context.getBean(PrepareGitHubCapture.class));
        });
    }
    @Test void missingOrInvalidBudgetsCannotBecomeDefaultsOrEchoRejectedValues() {
        for (var setting : new String[] {"ingestion.preparation-worker.batch-size=", "ingestion.preparation-worker.budget=private-invalid"}) {
            enabled().withPropertyValues(setting).run(context -> {
                var failure = context.getStartupFailure(); assertNotNull(failure);
                while (failure.getCause() != null) { failure = failure.getCause(); }
                assertEquals(IllegalArgumentException.class, failure.getClass());
                assertEquals("Explicit positive preparation batch and budget required", failure.getMessage());
                assertFalse(context.getStartupFailure().toString().contains("private-invalid"));
            });
        }
    }
    @Test void missingPreparationHandlerCannotInstallFallback() {
        dependencies().withPropertyValues("ingestion.preparation-worker.enabled=true").run(context -> {
            var failure = context.getStartupFailure(); assertNotNull(failure);
            while (failure.getCause() != null) { failure = failure.getCause(); }
            assertEquals(PrepareGitHubCapture.class, assertInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class, failure).getBeanType());
        });
    }
}
