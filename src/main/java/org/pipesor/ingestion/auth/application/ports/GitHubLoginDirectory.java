package org.pipesor.ingestion.auth.application.ports;

import java.time.Instant;
import java.util.List;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryFacts;

/** Reads the authenticated GitHub user and every repository of this App's installations the user can see. */
public interface GitHubLoginDirectory {
    Facts read(String userAccessToken, Instant accessExpiresAt);

    record Facts(String githubUserId, String login, List<RepositoryFacts> repositories) {
        public Facts { repositories = List.copyOf(repositories); }
        @Override public String toString() { return "GitHubLoginFacts[private]"; }
    }
}
