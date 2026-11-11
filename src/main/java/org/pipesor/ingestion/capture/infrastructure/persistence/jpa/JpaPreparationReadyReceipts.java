package org.pipesor.ingestion.capture.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.ports.PreparationReadyReceipts;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only hints without locks; source I/O starts only after a separate durable claim commits. */
public final class JpaPreparationReadyReceipts implements PreparationReadyReceipts {
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    public JpaPreparationReadyReceipts(EntityManager entityManager, PlatformTransactionManager manager) {
        this.entityManager = entityManager;
        transaction = new TransactionTemplate(manager); transaction.setReadOnly(true);
    }
    @Override public List<UUID> read(Instant now, int limit, UUID after) {
        if (now == null || limit < 1 || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CaptureFailure(CaptureFailure.Reason.INVALID_INPUT);
        }
        try {
            return transaction.execute(status -> entityManager.createNativeQuery("""
                    select id from ingestion.capture_receipts
                    where phase='ACCEPTED' and (lease_until is null or lease_until<=?1)
                    and (cast(?2 as uuid) is null or id>cast(?2 as uuid))
                    order by id
                    """, UUID.class).setParameter(1, now).setParameter(2, after).setMaxResults(limit).getResultList());
        } catch (RuntimeException unavailable) { throw new CaptureFailure(CaptureFailure.Reason.UNAVAILABLE); }
    }
}
