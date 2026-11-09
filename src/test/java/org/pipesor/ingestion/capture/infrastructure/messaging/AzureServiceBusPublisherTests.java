package org.pipesor.ingestion.capture.infrastructure.messaging;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.pipesor.ingestion.capture.application.ports.PublicationOutput;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AzureServiceBusPublisherTests {
    private PublicationOutput output() {
        var scope = new CaptureScope("machine", UUID.randomUUID(), "1", "2", "3", "4", "5", "rev-1");
        return new PublicationOutput(UUID.randomUUID(), scope, "corr-publish-17", "protected_workflow_evidence", 2,
                "{\"synthetic\":\"protected\"}".getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), 2);
    }
    @Test void forwardsFrozenBytesAndBoundBrokerMetadataOnEachStableAttempt() {
        var sender = mock(ServiceBusSenderClient.class);
        var publisher = new AzureServiceBusPublisher(sender, 1024);
        var output = output();
        publisher.publish(output); publisher.publish(output);
        var messages = ArgumentCaptor.forClass(ServiceBusMessage.class);
        verify(sender, times(2)).sendMessage(messages.capture());
        for (var message : messages.getAllValues()) {
            assertArrayEquals(output.body(), message.getBody().toBytes());
            assertEquals(output.id().toString(), message.getMessageId());
            assertEquals(output.correlationId(), message.getCorrelationId());
            assertEquals(output.contentType(), message.getContentType());
            assertEquals(output.applicationProperties(), message.getApplicationProperties());
        }
    }
    @Test void uncertainSdkFailureIsFixedAndDoesNotExposePrivateCause() {
        var sender = mock(ServiceBusSenderClient.class);
        doThrow(new IllegalStateException("private-sdk-secret")).when(sender).sendMessage(any(ServiceBusMessage.class));
        var error = assertThrows(CaptureFailure.class, () -> new AzureServiceBusPublisher(sender, 1024).publish(output()));
        assertEquals(CaptureFailure.Reason.UNAVAILABLE, error.reason());
        assertNull(error.getCause()); assertFalse(error.toString().contains("private-sdk-secret"));
    }
    @Test void oversizedOrTransactionalSendNeverReachesSdk() {
        var sender = mock(ServiceBusSenderClient.class);
        var error = assertThrows(CaptureFailure.class, () -> new AzureServiceBusPublisher(sender, 1).publish(output()));
        assertEquals(CaptureFailure.Reason.INVALID_INPUT, error.reason());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            error = assertThrows(CaptureFailure.class, () -> new AzureServiceBusPublisher(sender, 1024).publish(output()));
            assertEquals(CaptureFailure.Reason.INVALID_STATE, error.reason());
        } finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(sender);
    }
}
