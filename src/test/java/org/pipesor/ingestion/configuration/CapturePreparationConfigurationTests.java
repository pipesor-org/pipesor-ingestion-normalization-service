package org.pipesor.ingestion.configuration;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.commandhandlers.PrepareGitHubCapture;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture;
import org.pipesor.ingestion.capture.application.ports.ProtectedMessageEncoder;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubMachineTransport.ArchiveLimits;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapturePreparationConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CapturePreparationConfiguration.class);
    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("ingestion.capture-preparation.enabled=true",
                "ingestion.capture-preparation.max-compressed-bytes=200000",
                "ingestion.capture-preparation.max-expanded-bytes=800000",
                "ingestion.capture-preparation.max-archive-entries=30",
                "ingestion.github-machine.max-log-bytes=100000", "ingestion.protection.max-input-bytes=90000",
                "ingestion.protection.version=bounded-v1", "ingestion.messaging.max-message-bytes=120000")
                .withBean(Clock.class, Clock::systemUTC).withBean(CaptureHandler.class, () -> mock(CaptureHandler.class))
                .withBean(GitHubMachineCapture.class, () -> mock(GitHubMachineCapture.class))
                .withBean(NormalizeObservationHandler.class, () -> mock(NormalizeObservationHandler.class))
                .withBean(ProtectedMessageEncoder.class, () -> mock(ProtectedMessageEncoder.class));
    }
    @Test void disabledNeedsNoDependenciesOrLimits() {
        runner.run(context -> { assertNull(context.getStartupFailure()); assertFalse(context.containsBean("prepareGitHubCapture")); });
    }
    @Test void explicitBoundsComposeWithoutStartingWork() {
        enabled().run(context -> {
            assertNull(context.getStartupFailure()); assertNotNull(context.getBean(PrepareGitHubCapture.class));
            assertEquals(new ArchiveLimits(200000, 800000, 30, 100000), context.getBean(ArchiveLimits.class));
            verifyNoInteractions(context.getBean(GitHubMachineCapture.class), context.getBean(CaptureHandler.class));
        });
    }
    @Test void invalidBoundsCannotActivateAndDoNotEchoRejectedValues() {
        enabled().withPropertyValues("ingestion.capture-preparation.max-expanded-bytes=private-invalid").run(context -> {
            var failure = context.getStartupFailure(); assertNotNull(failure);
            while (failure.getCause() != null) { failure = failure.getCause(); }
            assertEquals(IllegalArgumentException.class, failure.getClass());
            assertEquals("Invalid or missing preparation setting: ingestion.capture-preparation.max-expanded-bytes", failure.getMessage());
            assertFalse(context.getStartupFailure().toString().contains("private-invalid"));
        });
    }
}
