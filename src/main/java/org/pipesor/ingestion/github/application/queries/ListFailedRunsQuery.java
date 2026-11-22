package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

/** One bounded page of a selected workflow's failed runs for the authenticated user. */
public record ListFailedRunsQuery(AccessContext context, RepositoryTarget repository, String workflowId, int page, int pageSize) {
    public ListFailedRunsQuery {
        Objects.requireNonNull(context, "Context required");
        Objects.requireNonNull(repository, "Repository required");
        AccessContext.requireText(workflowId);
        if (context.operation() != AccessOperation.DISCOVER || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Bounded discovery page required");
        }
    }
}
