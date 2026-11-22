package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.pipesor.ingestion.capture.application.commandhandlers.*;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.application.services.FailedRunEvidenceProfile;
import org.pipesor.ingestion.capture.infrastructure.configuration.CaptureAuthority;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubMachineTransport;
import org.pipesor.ingestion.capture.infrastructure.messaging.NormalizedOutputEncoder;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaCaptureLedger;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaPreparationReadyReceipts;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaPublicationReadyReceipts;
import org.pipesor.ingestion.capture.infrastructure.scheduling.PreparationRunner;
import org.pipesor.ingestion.capture.infrastructure.scheduling.PublicationRunner;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.infrastructure.protection.BoundedEvidenceProtection;
import org.pipesor.ingestion.normalization.interfaces.messaging.transform.NormalizedMessagesTransformer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Durable capture: requests record intent, the PreparationWorker protects and freezes outputs and the
 * PublicationWorker publishes them. Workers run unless {@code pipesor.capture.workers-enabled=false}
 * (automated tests only).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CaptureProperties.class, GitHubProperties.class})
public class CaptureConfiguration {
    @Bean ProtectedMessageEncoder protectedMessageEncoder() { return new NormalizedOutputEncoder(); }

    @Bean NormalizedMessagesTransformer normalizedMessagesTransformer() { return new NormalizedMessagesTransformer(); }

    @Bean CaptureLedger captureLedger(EntityManagerFactory factory, PlatformTransactionManager manager, ProtectedMessageEncoder encoder) {
        return new JpaCaptureLedger(SharedEntityManagerCreator.createSharedEntityManager(factory), manager, encoder);
    }

    @Bean BoundedEvidenceProtection evidenceProtection(CaptureProperties capture) {
        var protection = capture.protection();
        return new BoundedEvidenceProtection(protection.version(), protection.maxInputBytes(), protection.maxOutputChars(),
                protection.maxRedactions());
    }

    @Bean NormalizeObservationHandler normalizeObservationHandler(BoundedEvidenceProtection protection) {
        return new NormalizeObservationHandler(protection);
    }

    @Bean CaptureHandler captureHandler(CaptureLedger ledger, CaptureAuthority authority, BrokerPublisher publisher, Clock clock) {
        return new CaptureHandler(ledger, authority, publisher, clock);
    }

    @Bean FailedRunEvidenceProfile failedRunEvidenceProfile(CaptureProperties capture) {
        return new FailedRunEvidenceProfile(capture.archive().maxExpandedBytes(), capture.protection().maxInputBytes());
    }

    @Bean PrepareGitHubCapture prepareGitHubCapture(CaptureHandler capture, GitHubMachineTransport github,
            FailedRunEvidenceProfile profile, NormalizeObservationHandler normalizer, ProtectedMessageEncoder encoder,
            Clock clock, CaptureProperties properties) {
        return new PrepareGitHubCapture(capture, github, profile, normalizer, encoder, clock,
                properties.maxMessageBytes(), properties.protection().version());
    }

    @Bean RequestGitHubCapture requestGitHubCapture(CaptureHandler capture, GitHubMachineTransport github) {
        return new RequestGitHubCapture(capture, github);
    }

    @Bean GitHubAnalysisHandler githubAnalysisHandler(CaptureAuthority authority, RequestGitHubCapture capture,
            org.pipesor.ingestion.github.application.services.GitHubQueries github, GitHubProperties properties,
            org.pipesor.ingestion.shared.security.PlatformSecurityProperties platform, Clock clock) {
        return new GitHubAnalysisHandler(authority, capture, github, properties.captureDomain(), platform.tenantId(), clock);
    }

    @Bean PreparationReadyReceipts preparationReadyReceipts(EntityManagerFactory factory, PlatformTransactionManager manager) {
        return new JpaPreparationReadyReceipts(SharedEntityManagerCreator.createSharedEntityManager(factory), manager);
    }

    @Bean PreparationWorker preparationWorker(PreparationReadyReceipts ready, PrepareGitHubCapture handler, Clock clock,
            CaptureProperties capture) {
        return new PreparationWorker(ready, handler, clock, capture.preparation().batchSize(), capture.preparation().budget());
    }

    @Bean PublicationReadyReceipts publicationReadyReceipts(EntityManagerFactory factory, PlatformTransactionManager manager) {
        return new JpaPublicationReadyReceipts(SharedEntityManagerCreator.createSharedEntityManager(factory), manager);
    }

    @Bean PublicationWorker publicationWorker(PublicationReadyReceipts ready, CaptureHandler handler, Clock clock, CaptureProperties capture) {
        var publication = capture.publication();
        return new PublicationWorker(ready, handler, clock, publication.batchSize(), publication.lease(),
                publication.retryDelay(), publication.budget());
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "pipesor.capture.workers-enabled", matchIfMissing = true)
    PreparationRunner preparationRunner(PreparationWorker worker, Clock clock, CaptureProperties capture) {
        var preparation = capture.preparation();
        return new PreparationRunner(worker, clock, preparation.interval(), preparation.lease(),
                preparation.recoveryWindow(), preparation.shutdownTimeout());
    }

    @Bean
    @ConditionalOnBooleanProperty(name = "pipesor.capture.workers-enabled", matchIfMissing = true)
    PublicationRunner publicationRunner(PublicationWorker worker, CaptureProperties capture) {
        return new PublicationRunner(worker, capture.publication().interval(), capture.publication().shutdownTimeout());
    }
}
