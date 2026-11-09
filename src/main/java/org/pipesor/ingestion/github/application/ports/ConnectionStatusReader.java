package org.pipesor.ingestion.github.application.ports;

import java.util.Optional;
import org.pipesor.ingestion.github.application.queries.ConnectionStatus;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;

public interface ConnectionStatusReader {
    Optional<ConnectionStatus> find(ConnectionIdentity identity);
}
