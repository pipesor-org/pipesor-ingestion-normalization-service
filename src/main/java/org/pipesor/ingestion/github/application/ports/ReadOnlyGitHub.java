package org.pipesor.ingestion.github.application.ports;

import java.util.List;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.GitHubLink;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryFacts;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.pipesor.ingestion.github.domain.model.valueobjects.WorkflowMetadata;

/** Adapters resolve credentials privately and report effective user AND App access, including SSO.
 * Definitive connection loss/denial uses GitHubAccessFailure; other exceptions become UNAVAILABLE. */
public interface ReadOnlyGitHub {
    /** Resolve the actual user-token identity, never installation credentials; reuse this link generation for all reads. */
    Observation<Identity, String> currentUser(Identity request);
    Observation<RepositoryRequest, RepositoryFacts> repository(RepositoryRequest request);
    Observation<Identity, List<RepositoryFacts>> repositories(Identity request);
    Observation<RepositoryRequest, List<WorkflowMetadata>> workflows(RepositoryRequest request);
    Observation<WorkflowRequest, WorkflowMetadata> workflow(WorkflowRequest request);

    record Identity(AccessContext context, GitHubLink link) {}
    record RepositoryRequest(Identity identity, RepositoryTarget repository) {}
    record WorkflowRequest(RepositoryRequest repository, String workflowId) {}
}
