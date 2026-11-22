package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.infrastructure.messaging.NormalizedOutputEncoder;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaCaptureLedger;
import org.springframework.context.annotation.Bean;

import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/** The production ledger and capture handler around test doubles for scope and broker. */

public class TestLedgerConfiguration {
    @Bean ProtectedMessageEncoder protectedMessageEncoder() { return new NormalizedOutputEncoder(); }
    @Bean CaptureLedger captureLedger(EntityManagerFactory factory, PlatformTransactionManager manager, ProtectedMessageEncoder encoder) {
        return new JpaCaptureLedger(SharedEntityManagerCreator.createSharedEntityManager(factory), manager, encoder);
    }
    @Bean CaptureHandler captureHandler(CaptureLedger ledger, CurrentMachineScope scope, BrokerPublisher publisher, Clock clock) {
        return new CaptureHandler(ledger, scope, publisher, clock);
    }
}
