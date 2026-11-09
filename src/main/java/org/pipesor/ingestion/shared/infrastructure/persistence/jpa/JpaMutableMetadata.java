package org.pipesor.ingestion.shared.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/** Owner-assigned timestamps, also used by explicit SQL writes; never a concurrency token. */
@MappedSuperclass
public abstract class JpaMutableMetadata {
    @Id @Column(name = "id", nullable = false, updatable = false)
    protected UUID id;
    @Column(name = "created_at", nullable = false, updatable = false)
    protected Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    protected Instant updatedAt;
    public UUID id() { return id; }
    protected void metadata(UUID id, Instant createdAt, Instant updatedAt) {
        if (this.id != null && (!this.id.equals(id) || !this.createdAt.equals(createdAt))) {
            throw new IllegalArgumentException("Immutable persistence metadata");
        }
        this.id = id; this.createdAt = createdAt; this.updatedAt = updatedAt;
    }
}
