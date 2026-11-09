package org.pipesor.ingestion.capture.application.ports;

import java.util.List;
import java.util.UUID;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler.Result;

/** Deterministic CPU-only wire conversion; no network, policy calls or unprotected input. */
public interface ProtectedMessageEncoder {
    List<EncodedOutput> encode(Result protectedResult);
    final class EncodedOutput {
        private final UUID messageId;
        private final String eventType;
        private final int schemaMajor;
        private final byte[] body;
        public EncodedOutput(UUID messageId, String eventType, int schemaMajor, byte[] body) {
            this.messageId = messageId; this.eventType = eventType; this.schemaMajor = schemaMajor; this.body = body.clone();
        }
        public UUID messageId() { return messageId; }
        public String eventType() { return eventType; }
        public int schemaMajor() { return schemaMajor; }
        public byte[] body() { return body.clone(); }
        @Override public String toString() { return "EncodedOutput[redacted]"; }
    }
}
