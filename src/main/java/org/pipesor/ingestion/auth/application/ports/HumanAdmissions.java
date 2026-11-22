package org.pipesor.ingestion.auth.application.ports;

import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

/** Operator-owned admission of an installation account and repository for human use in a tenant. */
public interface HumanAdmissions {
    boolean admitted(UUID tenantId, String accountId, RepositoryTarget repository);
}
