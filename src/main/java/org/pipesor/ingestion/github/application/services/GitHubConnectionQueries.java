package org.pipesor.ingestion.github.application.services;

import java.util.Optional;
import org.pipesor.ingestion.github.application.queries.ConnectionStatus;
import org.pipesor.ingestion.github.application.queries.ConnectionStatusQuery;

public interface GitHubConnectionQueries {
    Optional<ConnectionStatus> status(ConnectionStatusQuery query);
}
