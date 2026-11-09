package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;
import java.util.Set;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

public record VerifyAccessQuery(AccessContext context, Set<RepositoryTarget> repositories) {
    public VerifyAccessQuery {
        Objects.requireNonNull(context, "Context required");
        repositories = Set.copyOf(repositories);
    }
}
