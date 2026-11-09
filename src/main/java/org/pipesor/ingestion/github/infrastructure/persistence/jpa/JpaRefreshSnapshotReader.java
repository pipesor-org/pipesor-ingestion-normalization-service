package org.pipesor.ingestion.github.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.pipesor.ingestion.github.application.ports.RefreshSnapshotReader;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No network work or entity escapes this root-locked local transaction. */
public final class JpaRefreshSnapshotReader implements RefreshSnapshotReader {
    private final JpaConnectionRepository repository;
    private final TransactionTemplate transaction;
    public JpaRefreshSnapshotReader(EntityManager entityManager, PlatformTransactionManager manager) {
        repository = new JpaConnectionRepository(entityManager); transaction = new TransactionTemplate(manager);
    }
    @Override public Snapshot read(UUID connectionId, UUID operationId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new ConnectionFailure(ConnectionFailure.Reason.INVALID_STATE);
        }
        try {
            return transaction.execute(status -> {
                var aggregate = operationId == null ? repository.connection(connectionId) : repository.operation(connectionId, operationId);
                var root = aggregate.root();
                var operation = operationId == null ? null : aggregate.operations().stream().filter(o -> o.id().equals(operationId)).findFirst().orElse(null);
                var generationId = operation == null ? root.activeGenerationId() : operation.generationId();
                var generation = aggregate.generations().stream().filter(g -> g.id().equals(generationId)).findFirst().orElse(null);
                return new Snapshot(root, generation, operation);
            });
        } catch (ConnectionFailure failure) { throw failure; }
        catch (RuntimeException privateFailure) { throw new ConnectionFailure(ConnectionFailure.Reason.UNAVAILABLE); }
    }
}
