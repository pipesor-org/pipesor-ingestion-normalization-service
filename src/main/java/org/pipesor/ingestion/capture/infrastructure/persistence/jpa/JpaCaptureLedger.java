package org.pipesor.ingestion.capture.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.*;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.domain.services.ObservationFingerprint;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.CaptureJpaEntities.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Short whole-phase transactions. Native SQL makes contention, fencing and exact byte retention explicit. */
public final class JpaCaptureLedger implements CaptureLedger {
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final ProtectedMessageEncoder encoder;
    public JpaCaptureLedger(EntityManager entityManager, PlatformTransactionManager manager, ProtectedMessageEncoder encoder) {
        this.entityManager = entityManager;
        this.encoder = java.util.Objects.requireNonNull(encoder);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private <T> T execute(Supplier<T> action) {
        try { return transaction.execute(status -> { entityManager.clear(); return action.get(); }); }
        catch (CaptureFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) { throw new CaptureFailure(CONFLICT); }
            }
            throw new CaptureFailure(UNAVAILABLE);
        }
    }
    private Query query(String sql, Object... arguments) {
        var query = entityManager.createNativeQuery(sql);
        for (int i = 0; i < arguments.length; i++) { query.setParameter(i + 1, arguments[i]); }
        return query;
    }
    private ReceiptRow row(UUID id, boolean lock) {
        var results = entityManager.createNativeQuery("select * from ingestion.capture_receipts where id = ?1" + (lock ? " for update" : ""), ReceiptRow.class)
                .setParameter(1, id).getResultList();
        if (results.isEmpty()) { throw new CaptureFailure(NOT_FOUND); }
        return (ReceiptRow) results.getFirst();
    }
    private void changed(int count) { if (count != 1) { throw new CaptureFailure(STALE_CLAIM); } }
    private static Instant technical(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static void future(Instant horizon, Instant now) { if (horizon == null || !technical(horizon).isAfter(now)) { throw new CaptureFailure(INVALID_INPUT); } }
    @Override public CaptureReceipt receipt(UUID id) { return execute(() -> row(id, false).snapshot()); }

    @Override public Optional<CaptureReceipt> findDelivery(CaptureScope scope, String source, String deliveryId) {
        return execute(() -> {
            var rows = entityManager.createNativeQuery("""
                    SELECT r.* FROM ingestion.capture_receipts r
                    JOIN ingestion.capture_delivery_aliases d ON d.receipt_id = r.id
                    WHERE d.capture_domain = ?1 AND d.source = ?2 AND d.delivery_id = ?3
                    """, ReceiptRow.class)
                    .setParameter(1, scope.captureDomain()).setParameter(2, source)
                    .setParameter(3, deliveryId).getResultList();
            if (rows.isEmpty()) return Optional.empty();
            var receipt = ((ReceiptRow) rows.getFirst()).snapshot();
            if (!scope.equals(receipt.scope())) throw new CaptureFailure(SCOPE_CHANGED);
            return Optional.of(receipt);
        });
    }

    @Override public CaptureReceipt accept(Accept command, Instant time) {
        return execute(() -> {
            var now = technical(time); future(command.replayUntil(), now);
            var scope = command.authenticatedScope();
            query("""
                    insert into ingestion.capture_receipts
                    (id,capture_domain,tenant_id,source,body_digest,app_id,account_id,installation_id,repository_id,workflow_id,
                     run_id,run_attempt,scope_revision,correlation_id,snapshot_message_id,evidence_message_id,phase,revision,claim_fence,created_at,updated_at,replay_until)
                    values (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,'ACCEPTED',0,0,?17,?17,?18)
                    on conflict (capture_domain,source,body_digest) do nothing
                    """, UUID.randomUUID(), scope.captureDomain(), scope.tenantId(), command.source(), command.authenticatedBodyDigest(),
                    scope.appId(), scope.accountId(), scope.installationId(), scope.repositoryId(), scope.workflowId(), command.runId(),
                    command.runAttempt(), scope.scopeRevision(), command.correlationId(), UUID.randomUUID(), UUID.randomUUID(), now, technical(command.replayUntil())).executeUpdate();
            var receipt = (ReceiptRow) entityManager.createNativeQuery("select * from ingestion.capture_receipts where capture_domain=?1 and source=?2 and body_digest=?3 for update", ReceiptRow.class)
                    .setParameter(1, scope.captureDomain()).setParameter(2, command.source()).setParameter(3, command.authenticatedBodyDigest()).getSingleResult();
            if (!receipt.tenantId.equals(scope.tenantId()) || !receipt.appId.equals(scope.appId()) || !receipt.accountId.equals(scope.accountId())
                    || !receipt.installationId.equals(scope.installationId()) || !receipt.repositoryId.equals(scope.repositoryId())
                    || !receipt.workflowId.equals(scope.workflowId()) || !receipt.runId.equals(command.runId()) || receipt.runAttempt != command.runAttempt()) {
                throw new CaptureFailure(CONFLICT);
            }
            query("""
                    insert into ingestion.capture_delivery_aliases (id,capture_domain,source,delivery_id,receipt_id,created_at)
                    values (?1,?2,?3,?4,?5,?6) on conflict (capture_domain,source,delivery_id) do nothing
                    """, UUID.randomUUID(), scope.captureDomain(), command.source(), command.deliveryId(), receipt.id(), now).executeUpdate();
            var target = query("select receipt_id from ingestion.capture_delivery_aliases where capture_domain=?1 and source=?2 and delivery_id=?3",
                    scope.captureDomain(), command.source(), command.deliveryId()).getSingleResult();
            if (!receipt.id().equals(target)) { throw new CaptureFailure(CONFLICT); }
            return receipt.snapshot();
        });
    }

    @Override public CaptureReceipt claimPreparation(Claim command, Instant time) {
        return execute(() -> {
            var now = technical(time); future(command.leaseUntil(), now);
            var current = row(command.receiptId(), true).snapshot();
            if (!"ACCEPTED".equals(current.phase())) { throw new CaptureFailure(INVALID_STATE); }
            if (current.leaseUntil() != null && current.leaseUntil().isAfter(now)) { throw new CaptureFailure(STALE_CLAIM); }
            changed(query("""
                    update ingestion.capture_receipts set claim_owner=?2,lease_until=?3,claim_fence=claim_fence+1,revision=revision+1,updated_at=?4
                    where id=?1 and phase='ACCEPTED' and (lease_until is null or lease_until<=?4)
                    """, current.id(), command.owner(), technical(command.leaseUntil()), now).executeUpdate());
            entityManager.clear(); return row(current.id(), false).snapshot();
        });
    }

    @Override public CaptureReceipt prepare(Prepare command, Instant time) {
        return execute(() -> {
            var now = technical(time); future(command.payloadRecoverUntil(), now);
            var receipt = row(command.receiptId(), true).snapshot();
            receipt.requireClaim(command.owner(), command.fence(), now);
            var result = command.protectedResult();
            receipt.requirePreparedObservation(result.observation(), result.snapshotMessageId(), result.evidenceMessageId());
            var source = result.observation();
            var fingerprint = ObservationFingerprint.of(receipt.scope(), source, command.fragmentLocator());
            var observationId = UUID.randomUUID();
            int count = source.execution().failed() ? 2 : 1;
            int inserted = query("""
                    insert into ingestion.capture_observations
                    (id,capture_domain,source,observation_key,protected_content_digest,origin_receipt_id,repository_id,workflow_id,run_id,run_attempt,
                    observed_at,observed_at_submicro,source_updated_at,source_updated_at_submicro,normalizer_version,protection_policy_version,correlation_id,required_output_count,created_at)
                    values (?1,?2,'github_actions',?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)
                    on conflict (capture_domain,source,observation_key) do nothing
                    """, observationId, receipt.scope().captureDomain(), fingerprint.observationKey(), fingerprint.protectedContentDigest(), receipt.id(),
                    receipt.scope().repositoryId(), receipt.scope().workflowId(), receipt.runId(), receipt.runAttempt(), technical(source.occurredAt()),
                    source.occurredAt().getNano() % 1000, technical(source.sourceUpdatedAt()), source.sourceUpdatedAt().getNano() % 1000,
                    command.normalizerVersion(), command.protectionPolicyVersion(), receipt.correlationId(), count, now).executeUpdate();
            if (inserted == 1) {
                var outputs = encoder.encode(result);
                validatePlan(receipt, outputs, count);
                for (var output : outputs) {
                    query("""
                            insert into ingestion.publication_outputs
                            (id,observation_id,event_type,schema_major,fragment_locator,payload,payload_digest,phase,revision,claim_fence,attempt_count,payload_recover_until,created_at,updated_at)
                            values (?1,?2,?3,?4,?5,?6,?7,'PENDING',0,0,0,?8,?9,?9)
                            """, output.messageId(), observationId, output.eventType(), output.schemaMajor(),
                            "execution.snapshot".equals(output.eventType()) ? null : command.fragmentLocator(), output.body(),
                            ObservationFingerprint.hash(output.body()), technical(command.payloadRecoverUntil()), now).executeUpdate();
                }
            } else {
                observationId = (UUID) query("select id from ingestion.capture_observations where capture_domain=?1 and source='github_actions' and observation_key=?2",
                        receipt.scope().captureDomain(), fingerprint.observationKey()).getSingleResult();
            }
            changed(query("""
                    update ingestion.capture_receipts set observation_id=?2,phase='PREPARED',prepared_at=?3,updated_at=?3,
                    revision=revision+1,claim_owner=null,lease_until=null where id=?1 and claim_owner=?4 and claim_fence=?5 and lease_until>?3
                    """, receipt.id(), observationId, now, command.owner(), command.fence()).executeUpdate());
            completeReceipts(observationId, now);
            entityManager.clear(); return row(receipt.id(), false).snapshot();
        });
    }
    private void validatePlan(CaptureReceipt receipt, List<ProtectedMessageEncoder.EncodedOutput> outputs, int required) {
        if (outputs == null || outputs.size() != required) { throw new CaptureFailure(CONFLICT); }
        int snapshot = 0; int evidence = 0;
        for (var output : outputs) {
            if (output == null || output.body().length == 0) { throw new CaptureFailure(CONFLICT); }
            if ("execution.snapshot".equals(output.eventType()) && output.schemaMajor() == 1 && receipt.snapshotMessageId().equals(output.messageId())) { snapshot++; }
            else if ("protected_workflow_evidence".equals(output.eventType()) && output.schemaMajor() == 2 && receipt.evidenceMessageId().equals(output.messageId())) { evidence++; }
            else { throw new CaptureFailure(CONFLICT); }
        }
        if (snapshot != 1 || evidence != required - 1) { throw new CaptureFailure(CONFLICT); }
    }
    @Override public void blockReceipt(UUID id, Instant time) {
        execute(() -> {
            var now = technical(time);
            var observed = row(id, false).snapshot().observationId();
            if (observed != null) { lockObservation(observed); }
            entityManager.clear();
            var current = row(id, true).snapshot();
            if (!java.util.Objects.equals(observed, current.observationId())) { throw new CaptureFailure(STALE_CLAIM); }
            query("""
                    update ingestion.capture_receipts set phase='BLOCKED',blocked_reason='SCOPE_CHANGED',claim_owner=null,lease_until=null,
                    revision=revision+1,updated_at=?2 where id=?1 and phase in ('ACCEPTED','PREPARED')
                    """, id, now).executeUpdate();
            return null;
        });
    }
    @Override public Optional<PublicationOutput> claimOutput(UUID observationId, UUID owner, Instant leaseUntil, Instant time) {
        return execute(() -> {
            var now = technical(time); future(leaseUntil, now);
            lockObservation(observationId);
            var candidates = entityManager.createNativeQuery("""
                    select p.* from ingestion.publication_outputs p
                    join ingestion.capture_observations o on o.id=p.observation_id
                    join ingestion.capture_receipts r on r.id=o.origin_receipt_id
                    where p.observation_id=?1 and r.phase='PREPARED'
                    and ((p.phase='PENDING' and (p.next_attempt_at is null or p.next_attempt_at<=?2))
                         or (p.phase='CLAIMED' and p.lease_until<=?2))
                    order by case when p.event_type='execution.snapshot' then 0 else 1 end,p.id for update of p skip locked limit 1
                    """, OutputRow.class).setParameter(1, observationId).setParameter(2, now).getResultList();
            if (candidates.isEmpty()) { return Optional.empty(); }
            var output = (OutputRow) candidates.getFirst();
            if (output.payload == null || !output.payloadDigest.equals(ObservationFingerprint.hash(output.payload))) { throw new CaptureFailure(CONFLICT); }
            query("""
                    update ingestion.publication_outputs set phase='CLAIMED',claim_owner=?2,lease_until=?3,claim_fence=claim_fence+1,
                    revision=revision+1,attempt_count=attempt_count+1,last_attempt_at=?4,updated_at=?4 where id=?1
                    """, output.id(), owner, technical(leaseUntil), now).executeUpdate();
            var origin = (UUID) query("select origin_receipt_id from ingestion.capture_observations where id=?1", observationId).getSingleResult();
            var receipt = row(origin, false).snapshot();
            return Optional.of(new PublicationOutput(output.id(), receipt.scope(), receipt.correlationId(), output.eventType,
                    output.schemaMajor, output.payload, owner, output.claimFence + 1));
        });
    }
    @Override public void confirm(PublicationOutput output, Instant time) {
        execute(() -> {
            var now = technical(time);
            // Serialize confirmations for the closed plan so the final receipt transition cannot be missed.
            var observationId = (UUID) query("select observation_id from ingestion.publication_outputs where id=?1", output.id()).getSingleResult();
            lockObservation(observationId);
            changed(query("""
                    update ingestion.publication_outputs set phase='CONFIRMED',confirmed_at=?4,claim_owner=null,lease_until=null,
                    revision=revision+1,updated_at=?4 where id=?1 and phase='CLAIMED' and claim_owner=?2 and claim_fence=?3 and lease_until>?4
                    """, output.id(), output.owner(), output.fence(), now).executeUpdate());
            completeReceipts(observationId, now); return null;
        });
    }
    private void completeReceipts(UUID observationId, Instant now) {
        query("""
                update ingestion.capture_receipts set phase='PUBLISHED',published_at=?2,updated_at=?2,revision=revision+1
                where observation_id=?1 and phase='PREPARED' and not exists
                (select 1 from ingestion.publication_outputs where observation_id=?1 and phase<>'CONFIRMED')
                """, observationId, now).executeUpdate();
    }
    private void lockObservation(UUID id) {
        query("select id from ingestion.capture_observations where id=?1 for update", id).getSingleResult();
    }
    private void lockOutputObservation(PublicationOutput output) {
        lockObservation((UUID) query("select observation_id from ingestion.publication_outputs where id=?1", output.id()).getSingleResult());
    }
    @Override public void retry(PublicationOutput output, Instant nextAttemptAt, Instant time) {
        execute(() -> {
            var now = technical(time);
            if (nextAttemptAt == null || technical(nextAttemptAt).isBefore(now)) { throw new CaptureFailure(INVALID_INPUT); }
            lockOutputObservation(output);
            changed(query("""
                    update ingestion.publication_outputs set phase='PENDING',next_attempt_at=?5,claim_owner=null,lease_until=null,
                    revision=revision+1,updated_at=?4 where id=?1 and phase='CLAIMED' and claim_owner=?2 and claim_fence=?3 and lease_until>?4
                    """, output.id(), output.owner(), output.fence(), now, technical(nextAttemptAt)).executeUpdate()); return null;
        });
    }
    @Override public void blockOutput(PublicationOutput output, Instant time) {
        execute(() -> {
            var now = technical(time);
            lockOutputObservation(output);
            changed(query("""
                    update ingestion.publication_outputs set phase='BLOCKED',blocked_reason='SCOPE_CHANGED',claim_owner=null,lease_until=null,
                    revision=revision+1,updated_at=?4 where id=?1 and phase='CLAIMED' and claim_owner=?2 and claim_fence=?3 and lease_until>?4
                    """, output.id(), output.owner(), output.fence(), now).executeUpdate()); return null;
        });
    }
    @Override public int purgeConfirmedPayloads(Instant time) {
        return execute(() -> {
            var now = technical(time);
            var observations = query("""
                    select o.id from ingestion.capture_observations o where exists
                    (select 1 from ingestion.publication_outputs p where p.observation_id=o.id and p.phase='CONFIRMED'
                     and p.payload is not null and p.payload_recover_until is not null and p.payload_recover_until<=?1)
                    order by o.id for update skip locked
                    """, now).getResultList();
            int purged = 0;
            for (var observation : observations) {
                purged += query("""
                        update ingestion.publication_outputs set payload=null,payload_removed_at=?1,updated_at=?1,revision=revision+1
                        where observation_id=?2 and phase='CONFIRMED' and payload is not null
                        and payload_recover_until is not null and payload_recover_until<=?1
                        """, now, observation).executeUpdate();
            }
            return purged;
        });
    }
}
