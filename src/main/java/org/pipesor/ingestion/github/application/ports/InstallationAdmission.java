package org.pipesor.ingestion.github.application.ports;

import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

public interface InstallationAdmission {
    Observation<Request, Boolean> read(Request request);

    record Request(ReadOnlyGitHub.Identity identity, String accountId, RepositoryTarget repository) {}
}
