package org.pipesor.ingestion.configuration;

import java.time.Clock;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.commandhandlers.PrepareGitHubCapture;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture;
import org.pipesor.ingestion.capture.application.ports.ProtectedMessageEncoder;
import org.pipesor.ingestion.capture.application.services.FailedRunEvidenceProfile;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubMachineTransport.ArchiveLimits;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Explicit receipt operation only; no scheduler, HTTP command or automatic recovery. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ingestion.capture-preparation.enabled", havingValue = "true")
public class CapturePreparationConfiguration {
    @Bean ArchiveLimits captureArchiveLimits(Environment env) {
        return new ArchiveLimits(number(env, "ingestion.capture-preparation.max-compressed-bytes"),
                number(env, "ingestion.capture-preparation.max-expanded-bytes"),
                number(env, "ingestion.capture-preparation.max-archive-entries"),
                number(env, "ingestion.github-machine.max-log-bytes"));
    }
    @Bean FailedRunEvidenceProfile failedRunEvidenceProfile(Environment env) {
        return new FailedRunEvidenceProfile(number(env, "ingestion.capture-preparation.max-expanded-bytes"),
                number(env, "ingestion.protection.max-input-bytes"));
    }
    @Bean PrepareGitHubCapture prepareGitHubCapture(CaptureHandler capture, GitHubMachineCapture github,
            FailedRunEvidenceProfile profile, NormalizeObservationHandler normalizer, ProtectedMessageEncoder encoder,
            Clock clock, Environment env) {
        String version;
        try {
            version = env.getRequiredProperty("ingestion.protection.version");
            org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope.safe(version);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid or missing preparation setting: ingestion.protection.version"); }
        return new PrepareGitHubCapture(capture, github, profile, normalizer, encoder, clock,
                number(env, "ingestion.messaging.max-message-bytes"), version);
    }
    private static int number(Environment env, String key) {
        try {
            int value = Integer.parseInt(env.getRequiredProperty(key));
            if (value < 1) { throw new IllegalArgumentException(); }
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid or missing preparation setting: " + key); }
    }
}
