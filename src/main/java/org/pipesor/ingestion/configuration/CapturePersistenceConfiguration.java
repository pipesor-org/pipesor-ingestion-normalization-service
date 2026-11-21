package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.infrastructure.messaging.NormalizedOutputEncoder;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaCaptureLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/** Required external ports deliberately have no production fallback and no worker is activated. */
@Configuration(proxyBeanMethods = false)
public class CapturePersistenceConfiguration {
    @Bean CaptureLedger captureLedger(EntityManagerFactory factory, PlatformTransactionManager manager, ProtectedMessageEncoder encoder) {
        return new JpaCaptureLedger(SharedEntityManagerCreator.createSharedEntityManager(factory), manager, encoder);
    }
    @Bean ProtectedMessageEncoder protectedMessageEncoder() { return new NormalizedOutputEncoder(); }
    @Bean
    @ConditionalOnProperty(name = "ingestion.capture.enabled", havingValue = "true")
    CaptureHandler captureHandler(CaptureLedger ledger, CurrentMachineScope scope,
            ObjectProvider<BrokerPublisher> publishers, Clock clock,
            @Value("${ingestion.local-capture.enabled:false}") boolean localCapture) {
        var publisher = publishers.getIfAvailable();
        if (publisher != null) return new CaptureHandler(ledger, scope, publisher, clock);
        if (!localCapture) throw new IllegalStateException("Capture requires a configured publisher");
        // An isolated capture can freeze its outbox while the publication transport is unavailable.
        return new CaptureHandler(ledger, scope, output -> {
            throw new IllegalStateException("Broker unavailable");
        }, clock);
    }
}
