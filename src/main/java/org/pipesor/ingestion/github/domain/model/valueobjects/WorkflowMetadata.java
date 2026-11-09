package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.util.Objects;

public record WorkflowMetadata(RepositoryTarget repository, String workflowId, String name, String file) {
    public WorkflowMetadata {
        Objects.requireNonNull(repository, "Repository required");
        AccessContext.requireText(workflowId);
        AccessContext.requireText(name);
        AccessContext.requireText(file);
    }
}
