package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub.CollaboratorsRequest;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

/**
 * One GitHub page of a repository's collaborators for a caller who currently administers it, each joined with whether
 * that person holds a Pipesor GitHub link (ADR-028: grants can only target linked users).
 */
public record ListCollaboratorsQuery(AccessContext context, RepositoryTarget repository, int page, int pageSize) {
    public ListCollaboratorsQuery {
        Objects.requireNonNull(context, "Context required");
        Objects.requireNonNull(repository, "Repository required");
        if (context.operation() != AccessOperation.DISCOVER || page < 1 || page > CollaboratorsRequest.MAX_PAGE
                || pageSize < 1 || pageSize > CollaboratorsRequest.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Bounded discovery page required");
        }
    }
}
