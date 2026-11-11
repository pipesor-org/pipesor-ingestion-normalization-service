package org.pipesor.ingestion.configuration;

import java.time.Clock;
import java.time.Duration;
import org.pipesor.ingestion.capture.application.commandhandlers.PreparationWorker;
import org.pipesor.ingestion.capture.infrastructure.scheduling.PreparationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Separate opt-in: enabling the explicit worker alone must never start scheduled work. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ingestion.preparation-scheduler.enabled", havingValue = "true")
public class PreparationSchedulerConfiguration {
    @Bean PreparationRunner preparationRunner(PreparationWorker worker, Clock clock, Environment env) {
        return new PreparationRunner(worker, clock, duration(env, "interval"), duration(env, "lease"),
                duration(env, "recovery-window"), duration(env, "shutdown-timeout"));
    }
    private static Duration duration(Environment env, String name) {
        try {
            var value = DurationStyle.detectAndParse(env.getRequiredProperty("ingestion.preparation-scheduler." + name));
            if (value.isNegative() || value.isZero()) { throw new IllegalArgumentException(); }
            value.toNanos();
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid or missing preparation scheduler setting: " + name);
        }
    }
}
