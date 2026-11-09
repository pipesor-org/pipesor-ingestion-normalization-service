package org.pipesor.ingestion.shared.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Stable owner-assigned identity, independent of an entity's mutable lifecycle. */
public abstract class Entity {
    private final UUID id;
    protected Entity(UUID id) { this.id = Objects.requireNonNull(id, "Identity required"); }
    public final UUID id() { return id; }
    @Override public final boolean equals(Object other) {
        return other != null && getClass() == other.getClass() && id.equals(((Entity) other).id);
    }
    @Override public final int hashCode() { return id.hashCode(); }
}
