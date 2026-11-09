package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.util.Objects;
import java.util.UUID;

/** Validated internal principal metadata; construction does not authenticate the caller. */
public record ConnectionIdentity(UUID tenantId, UUID objectId) {
    public ConnectionIdentity {
        Objects.requireNonNull(tenantId, "Tenant required");
        Objects.requireNonNull(objectId, "Object required");
    }
}
