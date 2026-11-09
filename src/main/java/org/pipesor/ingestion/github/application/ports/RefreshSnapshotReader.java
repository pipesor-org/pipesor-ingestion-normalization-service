package org.pipesor.ingestion.github.application.ports;

import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.*;

/** Short independent local transaction. Null operation means pre-claim read. Never evidence of restore safety. */
public interface RefreshSnapshotReader {
    Snapshot read(UUID connectionId, UUID operationId);
    record Snapshot(Root root, Generation generation, Operation operation) {
        @Override public String toString() { return "RefreshSnapshot[private]"; }
    }
}
