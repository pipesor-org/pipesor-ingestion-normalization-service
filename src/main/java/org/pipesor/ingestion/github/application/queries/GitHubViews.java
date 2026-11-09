package org.pipesor.ingestion.github.application.queries;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.pipesor.ingestion.github.domain.model.valueobjects.WorkflowMetadata;

public final class GitHubViews {
    private GitHubViews() {}

    public record RepositoryAccess(boolean admitted, boolean readable, boolean administrator) {}
    public record Access(AccessContext context, String githubUserId, Map<RepositoryTarget, RepositoryAccess> repositories,
                         Instant observedAt) {
        public Access { repositories = Map.copyOf(repositories); }
    }
    public record Repository(RepositoryTarget target, String fullName, String defaultBranch) {}
    public record Repositories(AccessContext context, String githubUserId, List<Repository> repositories) {
        public Repositories { repositories = List.copyOf(repositories); }
    }
    public record Workflows(AccessContext context, String githubUserId, Repository repository,
                            List<WorkflowMetadata> workflows) {
        public Workflows { workflows = List.copyOf(workflows); }
    }
    public record Selection(Access access, Repository repository, WorkflowMetadata workflow) {}
}
