package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

public record DiscoverWorkflowsQuery(DiscoverRepositoriesQuery discovery, RepositoryTarget repository) {
    public DiscoverWorkflowsQuery {
        Objects.requireNonNull(discovery, "Discovery context required");
        Objects.requireNonNull(repository, "Repository required");
    }
}
