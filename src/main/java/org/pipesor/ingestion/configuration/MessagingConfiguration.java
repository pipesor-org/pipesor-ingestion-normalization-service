package org.pipesor.ingestion.configuration;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.pipesor.ingestion.capture.application.ports.BrokerPublisher;
import org.pipesor.ingestion.capture.infrastructure.messaging.AzureServiceBusPublisher;
import org.pipesor.ingestion.shared.security.TransportPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Service Bus sender for the {@code workflow-evidence} topic: the emulator connection string under
 * {@code local}, the namespace with {@code DefaultAzureCredential} (managed identity) under {@code azure}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ServiceBusProperties.class, CaptureProperties.class})
public class MessagingConfiguration {
    @Bean(destroyMethod = "close")
    ServiceBusSenderClient serviceBusSender(ServiceBusProperties bus, TransportPolicy transport) {
        var retry = new AmqpRetryOptions().setTryTimeout(bus.tryTimeout()).setMaxRetries(bus.maxRetries())
                .setDelay(bus.retryDelay()).setMaxDelay(bus.retryMaxDelay());
        var builder = new ServiceBusClientBuilder().retryOptions(retry);
        if (bus.usesConnectionString()) {
            GitHubConfiguration.local(transport, "pipesor.service-bus.connection-string");
            builder.connectionString(bus.connectionString());
        } else {
            builder.credential(bus.namespace(), new DefaultAzureCredentialBuilder().build());
        }
        return builder.sender().topicName(bus.topic()).buildClient();
    }

    @Bean
    BrokerPublisher brokerPublisher(ServiceBusSenderClient sender, CaptureProperties capture) {
        return new AzureServiceBusPublisher(sender, capture.maxMessageBytes());
    }
}
