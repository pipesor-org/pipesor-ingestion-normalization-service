package org.pipesor.ingestion.github.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.queries.ConnectionStatus;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.ConnectionJpaEntities.RootRow;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JpaConnectionStatusReader implements ConnectionStatusReader {
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    public JpaConnectionStatusReader(EntityManager entityManager, PlatformTransactionManager manager) {
        this.entityManager = entityManager;
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
    }
    @Override public Optional<ConnectionStatus> find(ConnectionIdentity identity) {
        try {
            return transaction.execute(status -> entityManager.createQuery("select c from GitHubRootRow c "
                            + "where c.tenantId = :tenant and c.entraObjectId = :object", RootRow.class)
                    .setParameter("tenant", identity.tenantId()).setParameter("object", identity.objectId())
                    .getResultStream().findFirst().map(RootRow::snapshot)
                    .map(row -> new ConnectionStatus(row.id(), row.revision(), row.activeGenerationId(), row.createdAt(), row.updatedAt())));
        } catch (RuntimeException failure) { throw new ConnectionFailure(ConnectionFailure.Reason.UNAVAILABLE); }
    }
}
