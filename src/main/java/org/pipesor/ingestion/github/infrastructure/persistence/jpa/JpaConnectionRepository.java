package org.pipesor.ingestion.github.infrastructure.persistence.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import org.pipesor.ingestion.github.application.ports.ConnectionRepository;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.*;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.ConnectionJpaEntities.*;

/** Root locking is limited to a local command; CAS revisions also guard against stale submitted work. */
public final class JpaConnectionRepository implements ConnectionRepository {
    private final EntityManager entityManager;
    public JpaConnectionRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    @Override public GitHubConnection connection(UUID id) { return load(id, null, null, null, false); }
    @Override public GitHubConnection connectionForDisconnect(UUID id) { return load(id, null, null, null, true); }
    @Override public GitHubConnection attempt(UUID id, UUID attemptId) { return load(id, attemptId, null, null, false); }
    @Override public GitHubConnection generation(UUID id, UUID generationId) { return load(id, null, generationId, null, false); }
    @Override public GitHubConnection operation(UUID id, UUID operationId) { return load(id, null, null, operationId, false); }

    private GitHubConnection load(UUID id, UUID attemptId, UUID generationId, UUID operationId, boolean disconnect) {
        var row = entityManager.find(RootRow.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (row == null) { throw new ConnectionFailure(ConnectionFailure.Reason.NOT_FOUND); }
        var selectedGenerations = new HashSet<UUID>();
        if (row.activeGenerationId != null) { selectedGenerations.add(row.activeGenerationId); }
        if (generationId != null) { selectedGenerations.add(generationId); }
        var attempts = new ArrayList<Attempt>();
        if (attemptId != null) {
            var attempt = entityManager.find(AttemptRow.class, attemptId);
            if (attempt == null || !id.equals(attempt.connectionId)) { throw new ConnectionFailure(ConnectionFailure.Reason.NOT_FOUND); }
            attempts.add(attempt.snapshot());
            selectedGenerations.add(attempt.targetGenerationId);
        }
        if (disconnect) {
            entityManager.createQuery("select a from GitHubAttemptRow a where a.connectionId = :connection "
                            + "and a.phase in :pending", AttemptRow.class)
                    .setParameter("connection", id)
                    .setParameter("pending", java.util.List.of(AttemptPhase.PREPARING, AttemptPhase.READY, AttemptPhase.CLAIMED))
                    .getResultList().forEach(attempt -> {
                        attempts.add(attempt.snapshot()); selectedGenerations.add(attempt.targetGenerationId);
                    });
        }
        var operations = new ArrayList<Operation>();
        if (operationId != null) {
            var operation = entityManager.find(OperationRow.class, operationId);
            if (operation == null || !id.equals(operation.connectionId)) { throw new ConnectionFailure(ConnectionFailure.Reason.NOT_FOUND); }
            operations.add(operation.snapshot());
            selectedGenerations.add(operation.generationId);
        }
        var generationRows = entityManager.createQuery("select g from GitHubGenerationRow g where g.connectionId = :connection "
                        + "and (g.id in :selected or g.state = :blocked)", GenerationRow.class)
                .setParameter("connection", id).setParameter("selected", selectedGenerations)
                .setParameter("blocked", GenerationState.BLOCKED).getResultList();
        var generationIds = generationRows.stream().map(GenerationRow::id).toList();
        if (!generationIds.isEmpty()) {
            entityManager.createQuery("select o from GitHubOperationRow o where o.connectionId = :connection "
                            + "and o.generationId in :generations and o.kind = :kind and o.phase <> :completed", OperationRow.class)
                    .setParameter("connection", id).setParameter("generations", generationIds)
                    .setParameter("kind", Kind.REFRESH).setParameter("completed", Phase.COMPLETED).getResultList()
                    .forEach(operation -> { if (operations.stream().noneMatch(value -> value.id().equals(operation.id()))) {
                        operations.add(operation.snapshot());
                    }});
        }
        return new GitHubConnection(row.snapshot(), generationRows.stream().map(GenerationRow::snapshot).toList(), attempts, operations);
    }

    @Override public void insert(GitHubConnection connection) {
        var row = new RootRow(); row.apply(connection.root());
        // Assigned UUIDs are inserts, never Spring Data's merge heuristic.
        entityManager.persist(row);
        entityManager.flush();
    }

    @Override public void save(GitHubConnection connection) {
        var root = connection.root();
        var stored = entityManager.find(RootRow.class, root.id());
        if (!stored.snapshot().equals(root)) {
            int changed = entityManager.createNativeQuery("update ingestion.github_connections set revision = :revision, "
                            + "active_generation_id = :active, updated_at = :updated where id = :id and revision = :expected")
                    .setParameter("revision", root.revision()).setParameter("active", root.activeGenerationId())
                    .setParameter("updated", root.updatedAt()).setParameter("id", root.id())
                    .setParameter("expected", stored.revision).executeUpdate();
            if (changed != 1) { throw new ConnectionFailure(ConnectionFailure.Reason.CONFLICT); }
            entityManager.detach(stored);
        }
        // The immediate partial unique index requires removing the old ACTIVE row before adding the new one.
        for (var generation : connection.generations()) {
            if (generation.state() != GenerationState.ACTIVE) { saveGeneration(generation); }
        }
        entityManager.flush();
        for (var generation : connection.generations()) {
            if (generation.state() == GenerationState.ACTIVE) { saveGeneration(generation); }
        }
        for (var attempt : connection.attempts()) {
            var row = entityManager.find(AttemptRow.class, attempt.id());
            if (row == null) { row = new AttemptRow(); row.apply(attempt); entityManager.persist(row); }
            else if (!row.snapshot().equals(attempt)) { row.apply(attempt); }
        }
        for (var operation : connection.operations()) {
            var row = entityManager.find(OperationRow.class, operation.id());
            if (row == null) { row = new OperationRow(); row.apply(operation); entityManager.persist(row); }
            else if (!row.snapshot().equals(operation)) { row.apply(operation); }
        }
        entityManager.flush();
    }

    private void saveGeneration(Generation generation) {
        var row = entityManager.find(GenerationRow.class, generation.id());
        if (row == null) {
            row = new GenerationRow(); row.apply(generation); entityManager.persist(row); return;
        }
        if (row.snapshot().equals(generation)) { return; }
        int changed = entityManager.createNativeQuery("update ingestion.github_connection_generations set "
                        + "github_user_id = :user, state = :state, active_secret_version_ref = :version, "
                        + "access_expires_at = :accessExpiry, refresh_expires_at = :refreshExpiry, "
                        + "activated_at = :activated, revision = :revision, updated_at = :updated "
                        + "where id = :id and connection_id = :connection and revision = :expected")
                .setParameter("user", generation.githubUserId()).setParameter("state", generation.state().name())
                .setParameter("version", generation.activeSecretVersionRef() == null ? null : generation.activeSecretVersionRef().value())
                .setParameter("accessExpiry", generation.accessExpiresAt()).setParameter("refreshExpiry", generation.refreshExpiresAt())
                .setParameter("activated", generation.activatedAt()).setParameter("revision", generation.revision())
                .setParameter("updated", generation.updatedAt()).setParameter("id", generation.id())
                .setParameter("connection", generation.connectionId()).setParameter("expected", row.revision).executeUpdate();
        if (changed != 1) { throw new ConnectionFailure(ConnectionFailure.Reason.CONFLICT); }
        entityManager.detach(row);
    }
}
