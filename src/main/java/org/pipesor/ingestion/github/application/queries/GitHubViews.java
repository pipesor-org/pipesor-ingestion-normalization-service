package org.pipesor.ingestion.github.application.queries;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.pipesor.ingestion.github.domain.model.valueobjects.WorkflowMetadata;
import org.pipesor.ingestion.github.domain.model.valueobjects.GitHubLink;

public final class GitHubViews {
    private GitHubViews() {}

    public record RepositoryAccess(boolean admitted, boolean readable, boolean administrator, String accountId) {
        public RepositoryAccess(boolean admitted, boolean readable, boolean administrator) { this(admitted, readable, administrator, null); }
    }
    public record Access(AccessContext context, GitHubLink link, Map<RepositoryTarget, RepositoryAccess> repositories,
                         Instant observedAt) {
        public Access { repositories = Map.copyOf(repositories); }
        public String githubUserId() { return link.githubUserId(); }
    }
    public record Repository(RepositoryTarget target, String fullName, String defaultBranch) {}
    public record Repositories(AccessContext context, GitHubLink link, Instant observedAt, List<Repository> repositories) {
        public Repositories { repositories = List.copyOf(repositories); }
    }
    public record Workflows(AccessContext context, GitHubLink link, Instant observedAt, Repository repository,
                            List<WorkflowMetadata> workflows) {
        public Workflows { workflows = List.copyOf(workflows); }
    }
    /** One page of discovered repositories; {@code next} is null on the last page. */
    public record RepositoryPage(AccessContext context, GitHubLink link, Instant observedAt, List<Repository> repositories,
                                 DiscoveryPage.Position next) {
        public RepositoryPage { repositories = List.copyOf(repositories); }
    }
    /** One page of discovered workflows; {@code next} is null on the last page. */
    public record WorkflowPage(AccessContext context, GitHubLink link, Instant observedAt, Repository repository,
                               List<WorkflowMetadata> workflows, DiscoveryPage.Position next) {
        public WorkflowPage { workflows = List.copyOf(workflows); }
    }
    public record Runs(AccessContext context, GitHubLink link, Instant observedAt, Repository repository,
                       List<org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub.WorkflowRun> runs, boolean more) {
        public Runs { runs = List.copyOf(runs); }
    }
    public record Selection(Access access, Repository repository, WorkflowMetadata workflow) {}
    /** ADR-024: per repository, whether Pipesor admits it and whether the user administers it on GitHub. */
    public record RepositoryAdmission(String repositoryId, String fullName, boolean admitted, boolean userIsAdmin) {}
    public record Installation(String installationId, String accountId, String accountLogin, boolean active,
                               List<RepositoryAdmission> repositories) {
        public Installation { repositories = List.copyOf(repositories); }
    }
    public record Installations(AccessContext context, GitHubLink link, Instant observedAt, List<Installation> installations) {
        public Installations { installations = List.copyOf(installations); }
    }
}
