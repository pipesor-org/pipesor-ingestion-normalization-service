package org.pipesor.ingestion.github.application.queries;

import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;

public record ConnectionStatusQuery(ConnectionIdentity identity) {
    public ConnectionStatusQuery { java.util.Objects.requireNonNull(identity, "Identity required"); }
}
