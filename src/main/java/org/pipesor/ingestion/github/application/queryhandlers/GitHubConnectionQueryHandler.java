package org.pipesor.ingestion.github.application.queryhandlers;

import java.util.Optional;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.queries.ConnectionStatus;
import org.pipesor.ingestion.github.application.queries.ConnectionStatusQuery;
import org.pipesor.ingestion.github.application.services.GitHubConnectionQueries;

public final class GitHubConnectionQueryHandler implements GitHubConnectionQueries {
    private final ConnectionStatusReader reader;
    public GitHubConnectionQueryHandler(ConnectionStatusReader reader) { this.reader = java.util.Objects.requireNonNull(reader); }
    @Override public Optional<ConnectionStatus> status(ConnectionStatusQuery query) { return reader.find(query.identity()); }
}
