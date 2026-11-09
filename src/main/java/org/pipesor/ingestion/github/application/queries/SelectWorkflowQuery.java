package org.pipesor.ingestion.github.application.queries;

import java.util.Objects;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

public record SelectWorkflowQuery(AccessContext context, RepositoryTarget repository, String workflowId) {
    public SelectWorkflowQuery {
        Objects.requireNonNull(context, "Context required");
        Objects.requireNonNull(repository, "Repository required");
        AccessContext.requireText(workflowId);
        if (context.operation() != AccessOperation.REGISTER) {
            throw new IllegalArgumentException("Registration operation required");
        }
    }
}
