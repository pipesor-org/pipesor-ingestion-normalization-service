package org.pipesor.ingestion.configuration;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import org.pipesor.ingestion.capture.application.commandhandlers.PreparationWorker;
import org.pipesor.ingestion.capture.application.commandhandlers.PrepareGitHubCapture;
import org.pipesor.ingestion.capture.application.ports.PreparationReadyReceipts;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.JpaPreparationReadyReceipts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit bounded ticks only; does not schedule work or supply recovery/lease horizons. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ingestion.preparation-worker.enabled", havingValue = "true")
public class PreparationWorkerConfiguration {
    @Bean PreparationReadyReceipts preparationReadyReceipts(EntityManagerFactory factory, PlatformTransactionManager manager) {
        return new JpaPreparationReadyReceipts(SharedEntityManagerCreator.createSharedEntityManager(factory), manager);
    }
    @Bean PreparationWorker preparationWorker(PreparationReadyReceipts ready, PrepareGitHubCapture handler, Clock clock, Environment env) {
        int batch;
        Duration budget;
        try {
            batch = Integer.parseInt(env.getRequiredProperty("ingestion.preparation-worker.batch-size"));
            budget = DurationStyle.detectAndParse(env.getRequiredProperty("ingestion.preparation-worker.budget"));
            if (batch < 1 || budget.isNegative() || budget.isZero()) { throw new IllegalArgumentException(); }
            budget.toNanos();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Explicit positive preparation batch and budget required");
        }
        return new PreparationWorker(ready, handler, clock, batch, budget);
    }
}
