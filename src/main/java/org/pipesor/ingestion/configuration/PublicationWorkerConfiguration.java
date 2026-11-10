package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import org.pipesor.ingestion.capture.application.commandhandlers.*;
import org.pipesor.ingestion.capture.application.ports.PublicationReadyReceipts;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaPublicationReadyReceipts;
import org.pipesor.ingestion.capture.infrastructure.scheduling.PublicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="ingestion.publication-worker.enabled",havingValue="true")
public class PublicationWorkerConfiguration {
    @Bean PublicationReadyReceipts publicationReadyReceipts(EntityManagerFactory factory,PlatformTransactionManager manager) {
        return new JpaPublicationReadyReceipts(SharedEntityManagerCreator.createSharedEntityManager(factory),manager);
    }
    @Bean PublicationWorker publicationWorker(PublicationReadyReceipts ready,CaptureHandler handler,Clock clock,
            @Value("${ingestion.publication-worker.batch-size}") int batch,
            @Value("${ingestion.publication-worker.lease}") Duration lease,
            @Value("${ingestion.publication-worker.retry-delay}") Duration retry,
            @Value("${ingestion.publication-worker.budget}") Duration budget) {
        return new PublicationWorker(ready,handler,clock,batch,lease,retry,budget);
    }
    @Bean PublicationRunner publicationRunner(PublicationWorker worker,
            @Value("${ingestion.publication-worker.interval}") Duration interval,
            @Value("${ingestion.publication-worker.shutdown-timeout}") Duration shutdownTimeout) { return new PublicationRunner(worker,interval,shutdownTimeout); }
}
