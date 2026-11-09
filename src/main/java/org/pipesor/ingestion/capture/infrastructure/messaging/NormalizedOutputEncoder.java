package org.pipesor.ingestion.capture.infrastructure.messaging;

import java.util.List;
import org.pipesor.ingestion.capture.application.ports.ProtectedMessageEncoder;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler.Result;
import org.pipesor.ingestion.normalization.interfaces.messaging.transform.NormalizedMessagesTransformer;

public final class NormalizedOutputEncoder implements ProtectedMessageEncoder {
    private final NormalizedMessagesTransformer transformer = new NormalizedMessagesTransformer();
    @Override public List<EncodedOutput> encode(Result result) {
        return transformer.toMessages(result).stream().map(message -> new EncodedOutput(message.messageId(),
                (String) message.applicationProperties().get("eventType"),
                (Integer) message.applicationProperties().get("schemaMajor"), message.body())).toList();
    }
}
