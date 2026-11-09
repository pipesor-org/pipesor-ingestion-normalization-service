package org.pipesor.ingestion.github.application.ports;

import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection;

/** Each load selects only the root, relevant children and current/nonterminal lifecycle guards. */
public interface ConnectionRepository {
    GitHubConnection connection(UUID connectionId);
    GitHubConnection connectionForDisconnect(UUID connectionId);
    GitHubConnection attempt(UUID connectionId, UUID attemptId);
    GitHubConnection generation(UUID connectionId, UUID generationId);
    GitHubConnection operation(UUID connectionId, UUID operationId);
    void insert(GitHubConnection connection);
    void save(GitHubConnection connection);
}
