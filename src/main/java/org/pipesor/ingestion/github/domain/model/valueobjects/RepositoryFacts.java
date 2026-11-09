package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.util.Objects;

public record RepositoryFacts(RepositoryTarget target, String accountId, boolean installationActive,
                              boolean userReadable, boolean appReadable, boolean ssoAuthorized,
                              boolean administrator, String fullName, String defaultBranch) {
    public RepositoryFacts {
        Objects.requireNonNull(target, "Target required");
        AccessContext.requireText(accountId);
        AccessContext.requireText(fullName);
    }

    public boolean readable() { return installationActive && userReadable && appReadable && ssoAuthorized; }
}
