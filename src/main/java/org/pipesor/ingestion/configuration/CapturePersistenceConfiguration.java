package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.infrastructure.messaging.NormalizedOutputEncoder;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaCaptureLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/** Required external ports deliberately have no production fallback and no worker is activated. */
@Configuration(proxyBeanMethods = false)
public class CapturePersistenceConfiguration {
    @Bean CaptureLedger captureLedger(EntityManagerFactory factory, PlatformTransactionManager manager, ProtectedMessageEncoder encoder) {
        return new JpaCaptureLedger(SharedEntityManagerCreator.createSharedEntityManager(factory), manager, encoder);
    }
    @Bean ProtectedMessageEncoder protectedMessageEncoder() { return new NormalizedOutputEncoder(); }
    @Bean CaptureHandler captureHandler(CaptureLedger ledger, CurrentMachineScope scope,
            BrokerPublisher publisher, Clock clock) {
        return new CaptureHandler(ledger, scope, publisher, clock);
    }
}
