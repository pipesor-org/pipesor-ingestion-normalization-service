package org.pipesor.ingestion.configuration;

import org.pipesor.ingestion.normalization.infrastructure.protection.BoundedEvidenceProtection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ingestion.protection.enabled", havingValue = "true")
public class EvidenceProtectionConfiguration {
    @Bean
    BoundedEvidenceProtection evidenceProtection(Environment environment) {
        try {
            return new BoundedEvidenceProtection(environment.getRequiredProperty("ingestion.protection.version"),
                    environment.getRequiredProperty("ingestion.protection.max-input-bytes", Integer.class),
                    environment.getRequiredProperty("ingestion.protection.max-output-chars", Integer.class),
                    environment.getRequiredProperty("ingestion.protection.max-redactions", Integer.class));
        } catch (RuntimeException invalidConfiguration) {
            // Configuration conversion errors can echo rejected values; retain no source cause.
            throw new IllegalStateException("Required evidence protection configuration missing or invalid");
        }
    }
}
