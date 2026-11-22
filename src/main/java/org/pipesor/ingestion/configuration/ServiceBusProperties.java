package org.pipesor.ingestion.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Publication to the {@code workflow-evidence} topic. Exactly one transport: a connection string
 * (local profile only, Service Bus emulator) or a namespace used with {@code DefaultAzureCredential}.
 */
@ConfigurationProperties("pipesor.service-bus")
public record ServiceBusProperties(String connectionString, String namespace, String topic, Duration tryTimeout,
        int maxRetries, Duration retryDelay, Duration retryMaxDelay) {
    public ServiceBusProperties {
        boolean hasConnection = connectionString != null && !connectionString.isBlank();
        boolean hasNamespace = namespace != null && !namespace.isBlank();
        if (hasConnection == hasNamespace) {
            throw new IllegalArgumentException("Exactly one of pipesor.service-bus.connection-string or pipesor.service-bus.namespace is required");
        }
        if (hasNamespace && !namespace.matches("[a-zA-Z0-9][a-zA-Z0-9.-]*\\.servicebus\\.windows\\.net")) {
            throw new IllegalArgumentException("pipesor.service-bus.namespace must be a fully qualified Service Bus namespace");
        }
        if (topic == null || !topic.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException("pipesor.service-bus.topic is required");
        }
        if (tryTimeout == null || retryDelay == null || retryMaxDelay == null || tryTimeout.isNegative() || tryTimeout.isZero()
                || retryDelay.isNegative() || retryDelay.isZero() || retryMaxDelay.compareTo(retryDelay) < 0 || maxRetries < 0) {
            throw new IllegalArgumentException("Explicit pipesor.service-bus retry limits are required");
        }
    }

    public boolean usesConnectionString() { return connectionString != null && !connectionString.isBlank(); }

    @Override public String toString() { return "ServiceBusProperties[" + topic + "]"; }
}
