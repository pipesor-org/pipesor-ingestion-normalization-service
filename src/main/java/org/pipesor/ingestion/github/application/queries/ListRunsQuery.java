package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

/**
 * One bounded page of a selected workflow's completed runs with one conclusion for the authenticated user:
 * failed runs to analyse, or successful runs to record as incident verification.
 */
public record ListRunsQuery(AccessContext context, RepositoryTarget repository, String workflowId, String conclusion,
                            int page, int pageSize) {
    public static final java.util.Set<String> CONCLUSIONS = java.util.Set.of("failure", "success");

    public ListRunsQuery {
        Objects.requireNonNull(context, "Context required");
        Objects.requireNonNull(repository, "Repository required");
        AccessContext.requireText(workflowId);
        if (!CONCLUSIONS.contains(conclusion)) throw new IllegalArgumentException("Supported run conclusion required");
        if (context.operation() != AccessOperation.DISCOVER || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Bounded discovery page required");
        }
    }
}
