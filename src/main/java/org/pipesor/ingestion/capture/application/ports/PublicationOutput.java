package org.pipesor.ingestion.capture.application.ports;

import java.util.Map;
import java.util.UUID;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;

public final class PublicationOutput {
    private final UUID id;
    private final CaptureScope scope;
    private final String correlationId;
    private final String eventType;
    private final int schemaMajor;
    private final byte[] body;
    private final UUID owner;
    private final long fence;
    public PublicationOutput(UUID id, CaptureScope scope, String correlationId, String eventType, int schemaMajor,
            byte[] body, UUID owner, long fence) {
        this.id = id; this.scope = scope; this.correlationId = correlationId; this.eventType = eventType;
        this.schemaMajor = schemaMajor; this.body = body.clone(); this.owner = owner; this.fence = fence;
    }
    public UUID id() { return id; }
    public CaptureScope scope() { return scope; }
    public String correlationId() { return correlationId; }
    public String contentType() { return "application/json; charset=utf-8"; }
    public Map<String, Object> applicationProperties() { return Map.of("eventType", eventType, "schemaMajor", schemaMajor); }
    public byte[] body() { return body.clone(); }
    public UUID owner() { return owner; }
    public long fence() { return fence; }
    @Override public String toString() { return "PublicationOutput[redacted]"; }
}
