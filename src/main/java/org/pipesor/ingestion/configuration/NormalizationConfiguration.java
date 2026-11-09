package org.pipesor.ingestion.configuration;

import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.pipesor.ingestion.normalization.interfaces.messaging.transform.NormalizedMessagesTransformer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NormalizationConfiguration {
    @Bean
    NormalizeObservationHandler normalizeObservationHandler(ObjectProvider<EvidenceProtection> protection) {
        var policy = protection.getIfAvailable();
        return policy == null ? null : new NormalizeObservationHandler(policy);
    }

    @Bean
    NormalizedMessagesTransformer normalizedMessagesTransformer() {
        return new NormalizedMessagesTransformer();
    }
}
