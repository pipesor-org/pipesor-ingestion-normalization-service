package org.pipesor.ingestion.capture.infrastructure.messaging;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.util.Objects;
import org.pipesor.ingestion.capture.application.ports.BrokerPublisher;
import org.pipesor.ingestion.capture.application.ports.PublicationOutput;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Synchronous SDK acceptance; the ledger retains ownership of retries and confirmations. */
public final class AzureServiceBusPublisher implements BrokerPublisher {
    private final ServiceBusSenderClient sender;
    private final int maxMessageBytes;
    public AzureServiceBusPublisher(ServiceBusSenderClient sender, int maxMessageBytes) {
        this.sender = Objects.requireNonNull(sender);
        if (maxMessageBytes < 1) { throw new IllegalArgumentException("Explicit message limit required"); }
        this.maxMessageBytes = maxMessageBytes;
    }
    @Override public void publish(PublicationOutput output) {
        if (output == null || output.id() == null || output.correlationId() == null || output.body().length == 0
                || output.body().length > maxMessageBytes) { throw new CaptureFailure(INVALID_INPUT); }
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new CaptureFailure(INVALID_STATE); }
        var message = new ServiceBusMessage(output.body()).setMessageId(output.id().toString())
                .setCorrelationId(output.correlationId()).setContentType(output.contentType());
        message.getApplicationProperties().putAll(output.applicationProperties());
        try { sender.sendMessage(message); }
        catch (RuntimeException uncertain) { throw new CaptureFailure(UNAVAILABLE); }
    }
}
