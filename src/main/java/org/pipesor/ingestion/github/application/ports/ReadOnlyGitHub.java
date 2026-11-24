package org.pipesor.ingestion.github.application.ports;

import java.util.List;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.CollaboratorRole;
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
    /** The pipesor App installations visible to the user, each with the repositories GitHub currently lists in it. */
    Observation<Identity, List<InstallationRepositories>> installations(Identity request);
    Observation<RepositoryRequest, List<WorkflowMetadata>> workflows(RepositoryRequest request);
    Observation<WorkflowRequest, WorkflowMetadata> workflow(WorkflowRequest request);
    /** One bounded page of the workflow's failed runs, read with the user's token. */
    Observation<RunsRequest, RunPage> runs(RunsRequest request);
    /**
     * One GitHub page of the repository's collaborators ({@code affiliation=all}), read with the user's token only when
     * the user currently administers the repository; otherwise DENIED without asking GitHub for collaborators.
     */
    Observation<CollaboratorsRequest, CollaboratorPage> collaborators(CollaboratorsRequest request);

    record Identity(AccessContext context, GitHubLink link) {}
    record RepositoryRequest(Identity identity, RepositoryTarget repository) {}
    record WorkflowRequest(RepositoryRequest repository, String workflowId) {}
    record RunsRequest(WorkflowRequest workflow, String conclusion, int page, int pageSize) {}
    /** {@code page} is GitHub's 1-based page number, at most {@value #MAX_PAGE}; {@code pageSize} is 1-{@value #MAX_PAGE_SIZE}. */
    record CollaboratorsRequest(RepositoryRequest repository, int page, int pageSize) {
        public static final int MAX_PAGE = 1000;
        public static final int MAX_PAGE_SIZE = 100;
    }
    /** A human collaborator; {@code avatarUrl} is null unless GitHub supplied an absolute https URL. */
    record Collaborator(String githubUserId, String login, java.net.URI avatarUrl, CollaboratorRole role) {}
    /** Collaborators in GitHub's order; skipped bots and organizations shorten a page, only {@code more} signals another. */
    record CollaboratorPage(List<Collaborator> collaborators, boolean more) {
        public CollaboratorPage { collaborators = List.copyOf(collaborators); }
    }
    /** {@code runStartedAt} is when this attempt started; GitHub keeps {@code createdAt} from the first attempt. */
    record WorkflowRun(String runId, int attempt, String conclusion, String headBranch, String headSha,
                       java.time.Instant createdAt, java.time.Instant runStartedAt, java.net.URI htmlUrl) {
        public WorkflowRun(String runId, int attempt, String conclusion, String headBranch, String headSha,
                           java.time.Instant createdAt, java.net.URI htmlUrl) {
            this(runId, attempt, conclusion, headBranch, headSha, createdAt, null, htmlUrl);
        }
    }
    /** {@code active} is false for a suspended installation, which lists no repositories. */
    record InstallationRepositories(String installationId, String accountId, String accountLogin, boolean active,
                                    List<RepositoryFacts> repositories) {
        public InstallationRepositories { repositories = List.copyOf(repositories); }
    }
    record RunPage(List<WorkflowRun> runs, boolean more) {
        public RunPage { runs = List.copyOf(runs); }
    }
}
