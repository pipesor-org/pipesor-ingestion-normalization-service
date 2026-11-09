package org.pipesor.ingestion.configuration;

import com.azure.core.amqp.AmqpRetryOptions;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.time.Duration;
import org.pipesor.ingestion.capture.application.ports.BrokerPublisher;
import org.pipesor.ingestion.capture.infrastructure.messaging.AzureServiceBusPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ingestion.messaging.enabled", havingValue = "true")
public class ServiceBusPublisherConfiguration {
    @Bean(destroyMethod = "close")
    ServiceBusSenderClient serviceBusSender(
            @Value("${ingestion.messaging.namespace}") String namespace,
            @Value("${ingestion.messaging.topic}") String topic,
            @Value("${ingestion.messaging.try-timeout-millis}") long timeout,
            @Value("${ingestion.messaging.retry-delay-millis}") long delay,
            @Value("${ingestion.messaging.retry-max-delay-millis}") long maxDelay,
            @Value("${ingestion.messaging.max-retries}") int retries) {
        if (!namespace.matches("[a-zA-Z0-9][a-zA-Z0-9.-]*\\.servicebus\\.windows\\.net")
                || !topic.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")
                || timeout < 1 || delay < 1 || maxDelay < delay || retries < 0) {
            throw new IllegalArgumentException("Complete broker target and explicit retry limits required");
        }
        var retry = new AmqpRetryOptions().setTryTimeout(Duration.ofMillis(timeout)).setMaxRetries(retries)
                .setDelay(Duration.ofMillis(delay)).setMaxDelay(Duration.ofMillis(maxDelay));
        return new ServiceBusClientBuilder().credential(namespace, new ManagedIdentityCredentialBuilder().build())
                .retryOptions(retry).sender().topicName(topic).buildClient();
    }
    @Bean BrokerPublisher brokerPublisher(ServiceBusSenderClient sender,
            @Value("${ingestion.messaging.max-message-bytes}") int limit) {
        return new AzureServiceBusPublisher(sender, limit);
    }
}
