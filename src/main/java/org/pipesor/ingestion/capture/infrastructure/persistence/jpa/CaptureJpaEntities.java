package org.pipesor.ingestion.capture.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.shared.infrastructure.persistence.jpa.JpaMutableMetadata;

/** Flat owner-local mappings; SQL guards implement atomic ledger transitions and keep technical timestamps. */
public final class CaptureJpaEntities {
    private CaptureJpaEntities() {}
    @Entity(name = "CaptureReceiptRow")
    @Table(name = "capture_receipts", schema = "ingestion")
    public static class ReceiptRow extends JpaMutableMetadata {
        public ReceiptRow() {}
        String captureDomain;
        UUID tenantId;
        String source;
        String bodyDigest;
        String appId;
        String accountId;
        String installationId;
        String repositoryId;
        String workflowId;
        String runId;
        int runAttempt;
        String scopeRevision;
        String correlationId;
        UUID snapshotMessageId;
        UUID evidenceMessageId;
        String phase;
        long revision;
        long claimFence;
        UUID claimOwner;
        Instant leaseUntil;
        UUID observationId;
        String blockedReason;
        Instant preparedAt;
        Instant publishedAt;
        Instant replayUntil;
        CaptureReceipt snapshot() {
            return new CaptureReceipt(id, new CaptureScope(captureDomain, tenantId, appId, accountId, installationId,
                    repositoryId, workflowId, scopeRevision), runId, runAttempt, correlationId, snapshotMessageId,
                    evidenceMessageId, phase, observationId, claimOwner, claimFence, leaseUntil, blockedReason);
        }
    }
    @Entity(name = "CaptureDeliveryAliasRow")
    @Table(name = "capture_delivery_aliases", schema = "ingestion")
    public static class AliasRow {
        public AliasRow() {}
        @Id UUID id;
        String captureDomain;
        String source;
        String deliveryId;
        UUID receiptId;
        Instant createdAt;
    }
    @Entity(name = "CaptureObservationRow")
    @Table(name = "capture_observations", schema = "ingestion")
    public static class ObservationRow {
        public ObservationRow() {}
        @Id UUID id;
        String captureDomain;
        String source;
        String observationKey;
        String protectedContentDigest;
        UUID originReceiptId;
        String repositoryId;
        String workflowId;
        String runId;
        int runAttempt;
        Instant observedAt;
        int observedAtSubmicro;
        Instant sourceUpdatedAt;
        int sourceUpdatedAtSubmicro;
        String normalizerVersion;
        String protectionPolicyVersion;
        String correlationId;
        int requiredOutputCount;
        Instant createdAt;
    }
    @Entity(name = "PublicationOutputRow")
    @Table(name = "publication_outputs", schema = "ingestion")
    public static class OutputRow extends JpaMutableMetadata {
        public OutputRow() {}
        UUID observationId;
        String eventType;
        int schemaMajor;
        String fragmentLocator;
        @Column(columnDefinition = "bytea") byte[] payload;
        String payloadDigest;
        String phase;
        long revision;
        long claimFence;
        long attemptCount;
        UUID claimOwner;
        Instant leaseUntil;
        Instant nextAttemptAt;
        Instant lastAttemptAt;
        Instant confirmedAt;
        Instant payloadRemovedAt;
        Instant payloadRecoverUntil;
        String blockedReason;
    }
}
