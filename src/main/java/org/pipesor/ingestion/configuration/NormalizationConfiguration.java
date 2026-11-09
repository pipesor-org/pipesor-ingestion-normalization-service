package org.pipesor.ingestion.configuration;

import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.pipesor.ingestion.normalization.interfaces.messaging.transform.NormalizedMessagesTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NormalizationConfiguration {
    @Bean
    NormalizeObservationHandler normalizeObservationHandler(EvidenceProtection protection) {
        return new NormalizeObservationHandler(protection);
    }

    @Bean
    NormalizedMessagesTransformer normalizedMessagesTransformer() {
        return new NormalizedMessagesTransformer();
    }
}
