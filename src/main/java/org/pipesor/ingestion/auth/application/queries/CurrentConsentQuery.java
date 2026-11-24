package org.pipesor.ingestion.auth.application.queries;

import java.util.Objects;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;

/** The caller's current login consent; the identity comes from the verified session token. */
public record CurrentConsentQuery(ConnectionIdentity identity) {
    public CurrentConsentQuery { Objects.requireNonNull(identity, "Identity required"); }
}
