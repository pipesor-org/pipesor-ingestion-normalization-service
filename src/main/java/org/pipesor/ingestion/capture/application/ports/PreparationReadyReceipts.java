package org.pipesor.ingestion.capture.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded hints; ledger claims gate reads, which still require current machine-scope authorization. */
public interface PreparationReadyReceipts {
    List<UUID> read(Instant now, int limit, UUID after);
}
