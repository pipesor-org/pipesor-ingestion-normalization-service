package org.pipesor.ingestion.github.domain.model.valueobjects;

public record RepositoryTarget(String repositoryId, String installationId) {
    public RepositoryTarget {
        AccessContext.requireText(repositoryId);
        AccessContext.requireText(installationId);
    }
}
