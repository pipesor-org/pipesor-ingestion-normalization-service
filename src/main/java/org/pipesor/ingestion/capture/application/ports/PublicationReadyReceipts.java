package org.pipesor.ingestion.capture.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded hints only; output claims remain the authority under concurrent workers. */
public interface PublicationReadyReceipts {
    List<UUID> read(Instant now, int limit, UUID after);
}
