package org.pipesor.ingestion.capture.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.ports.PublicationReadyReceipts;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only discovery; no row locks or network work. The origin owns the frozen publication plan. */
public final class JpaPublicationReadyReceipts implements PublicationReadyReceipts {
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    public JpaPublicationReadyReceipts(EntityManager entityManager, PlatformTransactionManager manager) {
        this.entityManager=entityManager;transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);
    }
    @Override public List<UUID> read(Instant now,int limit,UUID after) {
        if(now==null || limit<1 || TransactionSynchronizationManager.isActualTransactionActive()) throw new CaptureFailure(CaptureFailure.Reason.INVALID_INPUT);
        try {
            return transaction.execute(status -> entityManager.createNativeQuery("""
                    select r.id from ingestion.capture_receipts r
                    join ingestion.capture_observations o on o.id=r.observation_id and o.origin_receipt_id=r.id
                    where r.phase='PREPARED' and exists (
                        select 1 from ingestion.publication_outputs p where p.observation_id=o.id and
                        ((p.phase='PENDING' and (p.next_attempt_at is null or p.next_attempt_at<=?1))
                        or (p.phase='CLAIMED' and p.lease_until<=?1)))
                    and (cast(?2 as uuid) is null or r.id>cast(?2 as uuid))
                    order by r.id
                    """,UUID.class).setParameter(1,now).setParameter(2,after).setMaxResults(limit).getResultList());
        } catch(RuntimeException failure) { throw new CaptureFailure(CaptureFailure.Reason.UNAVAILABLE); }
    }
}
