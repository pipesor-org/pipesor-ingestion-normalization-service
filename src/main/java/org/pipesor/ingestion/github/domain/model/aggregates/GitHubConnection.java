package org.pipesor.ingestion.github.domain.model.aggregates;

import java.time.Instant;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.pipesor.ingestion.shared.domain.model.Entity;

import static org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure.Reason.*;

/**
 * Consistency boundary for one Entra principal. Children are loaded for the current command;
 * database constraints cover membership and exclusivity across the remaining history.
 * Every method is one local transaction; remote work happens only between these methods.
 */
public final class GitHubConnection extends Entity {
    public enum Outcome { CLAIMED, ACTIVATED, CONFLICT, EXPIRED }
    private Root root;
    private final Map<UUID, Generation> generations = new LinkedHashMap<>();
    private final Map<UUID, Attempt> attempts = new LinkedHashMap<>();
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();

    public GitHubConnection(Root root, Collection<Generation> generations,
                            Collection<Attempt> attempts, Collection<Operation> operations) {
        super(root.id());
        this.root = Objects.requireNonNull(root, "Connection required");
        generations.forEach(value -> { belongs(value.connectionId()); this.generations.put(value.id(), value); });
        attempts.forEach(value -> { belongs(value.connectionId());
            require(root.identity().equals(value.identity()), INVALID_STATE); this.attempts.put(value.id(), value); });
        operations.forEach(value -> { belongs(value.connectionId()); this.operations.put(value.id(), value); });
        require(root.revision() >= 0, INVALID_STATE);
        if (root.activeGenerationId() != null) {
            require(generation(root.activeGenerationId()).state() == GenerationState.ACTIVE, INVALID_STATE);
        }
        this.generations.values().forEach(value -> {
            if (value.state() == GenerationState.ACTIVE) { require(value.id().equals(root.activeGenerationId()), INVALID_STATE); }
        });
    }

    public static GitHubConnection create(UUID id, ConnectionIdentity identity, Instant now) {
        return new GitHubConnection(new Root(Objects.requireNonNull(id), Objects.requireNonNull(identity),
                0, null, now, now), java.util.List.of(), java.util.List.of(), java.util.List.of());
    }

    public Root root() { return root; }
    public Collection<Generation> generations() { return java.util.List.copyOf(generations.values()); }
    public Collection<Attempt> attempts() { return java.util.List.copyOf(attempts.values()); }
    public Collection<Operation> operations() { return java.util.List.copyOf(operations.values()); }

    public void prepare(UUID attemptId, UUID generationId, long revision, UUID expectedActive,
                        StateDigest digest, Instant expiresAt, SecretReference credentialObject,
                        SecretReference pkceObject, String approvedReturnRef, String correlationId, Instant now) {
        expect(revision, expectedActive);
        require(expiresAt.isAfter(now), EXPIRED);
        Objects.requireNonNull(digest, "Digest required");
        Objects.requireNonNull(credentialObject, "Credential object required");
        Objects.requireNonNull(pkceObject, "PKCE object required");
        AccessContext.requireText(approvedReturnRef);
        AccessContext.requireText(correlationId);
        require(SecretReference.generationObject(generationId).equals(credentialObject)
                && SecretReference.pkceObject(attemptId).equals(pkceObject), INVALID_STATE);
        require(!attempts.containsKey(attemptId) && !generations.containsKey(generationId), CONFLICT);
        generations.put(generationId, new Generation(generationId, root.id(), null, GenerationState.PREPARING,
                credentialObject, null, null, null, null, 0, now, now));
        attempts.put(attemptId, new Attempt(attemptId, root.id(), root.identity(), revision, expectedActive,
                generationId, digest, expiresAt, AttemptPhase.PREPARING, null, pkceObject, null,
                CleanupState.HELD, null, approvedReturnRef, correlationId, now, now));
    }

    public void ready(UUID attemptId, SavedVersion pkceVersion, Instant now) {
        var attempt = attempt(attemptId);
        require(attempt.phase() == AttemptPhase.PREPARING, INVALID_STATE);
        require(attempt.expiresAt().isAfter(now), EXPIRED);
        expect(attempt.expectedConnectionRevision(), attempt.expectedGenerationId());
        require(attempt.pkceSecretObjectRef().equals(pkceVersion.object()), INVALID_STATE);
        attempts.put(attempt.id(), copy(attempt, AttemptPhase.READY, null, pkceVersion.version(),
                CleanupState.HELD, null, now));
    }

    public Outcome claim(UUID attemptId, ConnectionIdentity identity, StateDigest digest, Instant now) {
        var attempt = attempt(attemptId);
        require(root.identity().equals(identity) && attempt.stateDigest().equals(digest), CONFLICT);
        require(attempt.phase() == AttemptPhase.READY, INVALID_STATE);
        if (!attempt.expiresAt().isAfter(now)) {
            terminate(attempt, AttemptPhase.EXPIRED, now);
            return Outcome.EXPIRED;
        }
        if (!matches(attempt.expectedConnectionRevision(), attempt.expectedGenerationId())) {
            terminate(attempt, AttemptPhase.FAILED, now);
            return Outcome.CONFLICT;
        }
        require(generation(attempt.targetGenerationId()).state() == GenerationState.PREPARING, CONFLICT);
        attempts.put(attempt.id(), copy(attempt, AttemptPhase.CLAIMED, now, attempt.pkceSecretVersionRef(),
                CleanupState.HELD, null, now));
        return Outcome.CLAIMED;
    }

    public Outcome activate(UUID attemptId, String githubUserId, SavedVersion credentialVersion,
                            Instant accessExpiresAt, Instant refreshExpiresAt, Instant now) {
        var attempt = attempt(attemptId);
        require(attempt.phase() == AttemptPhase.CLAIMED, INVALID_STATE);
        var target = generation(attempt.targetGenerationId());
        require(target.secretObjectRef().equals(credentialVersion.object()), INVALID_STATE);
        AccessContext.requireText(githubUserId);
        require(accessExpiresAt.isAfter(now) && refreshExpiresAt.isAfter(now), EXPIRED);
        if (!attempt.expiresAt().isAfter(now)) {
            terminate(attempt, AttemptPhase.EXPIRED, now);
            return Outcome.EXPIRED;
        }
        if (!matches(attempt.expectedConnectionRevision(), attempt.expectedGenerationId())
                || target.state() != GenerationState.PREPARING || target.revision() != 0) {
            terminate(attempt, AttemptPhase.FAILED, now);
            return Outcome.CONFLICT;
        }
        invalidateCurrent(now);
        invalidateBlocked(now);
        generations.put(target.id(), new Generation(target.id(), root.id(), githubUserId, GenerationState.ACTIVE,
                target.secretObjectRef(), credentialVersion.version(), accessExpiresAt, refreshExpiresAt,
                now, target.revision() + 1, target.createdAt(), now));
        root = new Root(root.id(), root.identity(), root.revision() + 1, target.id(), root.createdAt(), now);
        attempts.put(attempt.id(), copy(attempt, AttemptPhase.COMPLETED, attempt.consumedAt(),
                attempt.pkceSecretVersionRef(), CleanupState.PENDING, null, now));
        return Outcome.ACTIVATED;
    }

    public void abandon(UUID attemptId, boolean expired, Instant now) {
        var attempt = attempt(attemptId);
        require(!terminal(attempt.phase()), INVALID_STATE);
        if (expired) { require(!attempt.expiresAt().isAfter(now), INVALID_STATE); }
        terminate(attempt, expired ? AttemptPhase.EXPIRED : AttemptPhase.FAILED, now);
    }

    public void finishPkceCleanup(UUID attemptId, Result result, boolean noLateWritePossible, Instant now) {
        var attempt = attempt(attemptId);
        require(terminal(attempt.phase()) && attempt.pkceCleanupState() != CleanupState.COMPLETED, INVALID_STATE);
        require(result != null && result != Result.PENDING, INVALID_STATE);
        boolean completed = successful(result) && noLateWritePossible;
        attempts.put(attempt.id(), copy(attempt, attempt.phase(), attempt.consumedAt(),
                attempt.pkceSecretVersionRef(), completed ? CleanupState.COMPLETED : CleanupState.BLOCKED, result, now));
    }

    public void claimRefresh(UUID operationId, UUID generationId, long connectionRevision,
                             long generationRevision, UUID owner, Instant deadline, Instant now) {
        var generation = current(generationId, connectionRevision, generationRevision);
        require(generation.accessExpiresAt() != null && generation.refreshExpiresAt().isAfter(now), EXPIRED);
        require(deadline.isAfter(now), EXPIRED);
        Objects.requireNonNull(owner, "Owner required");
        require(operations.values().stream().noneMatch(value -> value.generationId().equals(generationId)
                && value.kind() == Kind.REFRESH && value.phase() != Phase.COMPLETED), CONFLICT);
        require(!operations.containsKey(operationId), CONFLICT);
        operations.put(operationId, new Operation(operationId, root.id(), generationId, connectionRevision,
                generationRevision, Kind.REFRESH, Phase.CLAIMED, owner, 1, now, deadline,
                null, null, null, null, null, null, now, now));
    }

    public void requestMayBeInFlight(UUID operationId, UUID owner, long fence, Instant now) {
        var operation = owned(operationId, owner, fence, now);
        require(operation.kind() == Kind.REFRESH && operation.phase() == Phase.CLAIMED, INVALID_STATE);
        current(operation.generationId(), operation.expectedConnectionRevision(), operation.expectedGenerationRevision());
        operations.put(operation.id(), copy(operation, Phase.REQUEST_MAY_BE_IN_FLIGHT,
                operation.candidateSecretVersionRef(), operation.upstreamResult(), operation.vaultResult(), null, now));
    }

    public void saveRefreshVersion(UUID operationId, UUID owner, long fence, SavedVersion candidate, Instant now) {
        var operation = owner(operationId, owner, fence);
        require(operation.kind() == Kind.REFRESH && (operation.phase() == Phase.REQUEST_MAY_BE_IN_FLIGHT
                || operation.phase() == Phase.BLOCKED), INVALID_STATE);
        var generation = generation(operation.generationId());
        require(generation.secretObjectRef().equals(candidate.object())
                && !Objects.equals(generation.activeSecretVersionRef(), candidate.version()), INVALID_STATE);
        require(operation.candidateSecretVersionRef() == null
                || operation.candidateSecretVersionRef().equals(candidate.version()), CONFLICT);
        boolean blocked = operation.phase() == Phase.BLOCKED || !operation.deadlineAt().isAfter(now);
        operations.put(operation.id(), copy(operation, blocked ? Phase.BLOCKED : Phase.VERSION_SAVED,
                candidate.version(), null, null, null, now));
        if (blocked) { blockRefresh(operationId, owner, fence, now); }
    }

    public Outcome activateRefresh(UUID operationId, UUID owner, long fence,
                                    Instant accessExpiry, Instant refreshExpiry, Instant now) {
        var operation = owner(operationId, owner, fence);
        require(operation.kind() == Kind.REFRESH && operation.phase() == Phase.VERSION_SAVED, INVALID_STATE);
        if (!operation.deadlineAt().isAfter(now)) {
            blockRefresh(operationId, owner, fence, now);
            return Outcome.EXPIRED;
        }
        var generation = generation(operation.generationId());
        require(accessExpiry.isAfter(now) && refreshExpiry.isAfter(now), EXPIRED);
        if (!matches(operation.expectedConnectionRevision(), generation.id())
                || generation.revision() != operation.expectedGenerationRevision()
                || generation.state() != GenerationState.ACTIVE) {
            operations.put(operation.id(), copy(operation, Phase.BLOCKED, operation.candidateSecretVersionRef(),
                    null, null, null, now));
            return Outcome.CONFLICT;
        }
        // Preserve the old exact version as a distinct cleanup target before publishing its replacement.
        cleanup(generation, Scope.EXACT_VERSION, generation.activeSecretVersionRef(), now);
        generations.put(generation.id(), new Generation(generation.id(), root.id(), generation.githubUserId(),
                GenerationState.ACTIVE, generation.secretObjectRef(), operation.candidateSecretVersionRef(),
                accessExpiry, refreshExpiry, generation.activatedAt(), generation.revision() + 1,
                generation.createdAt(), now));
        root = new Root(root.id(), root.identity(), root.revision() + 1, generation.id(), root.createdAt(), now);
        operations.put(operation.id(), copy(operation, Phase.COMPLETED, operation.candidateSecretVersionRef(),
                null, null, now, now));
        return Outcome.ACTIVATED;
    }

    /** Uncertain refresh remains nonterminal forever unless an explicitly accepted recovery resolves it. */
    public void blockRefresh(UUID operationId, UUID owner, long fence, Instant now) {
        var operation = owner(operationId, owner, fence);
        require(operation.kind() == Kind.REFRESH && operation.phase() != Phase.COMPLETED, INVALID_STATE);
        var generation = generation(operation.generationId());
        operations.put(operation.id(), copy(operation, Phase.BLOCKED, operation.candidateSecretVersionRef(),
                null, null, null, now));
        if (generation.state() == GenerationState.ACTIVE) {
            generations.put(generation.id(), state(generation, GenerationState.BLOCKED, now));
            root = new Root(root.id(), root.identity(), root.revision() + 1, null, root.createdAt(), now);
        }
    }

    public void disconnect(long expectedRevision, Instant now) {
        require(root.revision() == expectedRevision, CONFLICT);
        invalidateCurrent(now);
        invalidateBlocked(now);
        for (var attempt : java.util.List.copyOf(attempts.values())) {
            if (!terminal(attempt.phase())) { terminate(attempt, AttemptPhase.FAILED, now); }
        }
        root = new Root(root.id(), root.identity(), root.revision() + 1, null, root.createdAt(), now);
    }

    private void invalidateBlocked(Instant now) {
        for (var generation : java.util.List.copyOf(generations.values())) {
            if (generation.state() == GenerationState.BLOCKED) {
                generations.put(generation.id(), state(generation, GenerationState.INVALIDATED, now));
                cleanup(generation, Scope.INVALIDATED_GENERATION, null, now);
            }
        }
    }

    public void claimCleanup(UUID operationId, UUID owner, Instant deadline, Instant now) {
        var operation = operation(operationId);
        require(operation.kind() == Kind.CLEANUP && (operation.phase() == Phase.PENDING
                || operation.phase() == Phase.BLOCKED), INVALID_STATE);
        cleanupTarget(operation);
        require(deadline.isAfter(now), EXPIRED);
        Objects.requireNonNull(owner, "Owner required");
        operations.put(operation.id(), new Operation(operation.id(), root.id(), operation.generationId(),
                operation.expectedConnectionRevision(), operation.expectedGenerationRevision(), operation.kind(),
                Phase.CLAIMED, owner, operation.fence() + 1, now, deadline, null,
                operation.cleanupTargetVersionRef(), operation.cleanupScope(), operation.upstreamResult(),
                operation.vaultResult(), null, operation.createdAt(), now));
    }

    public void finishCleanup(UUID operationId, UUID owner, long fence, Result upstream, Result vault,
                              boolean noLateWritePossible, Instant now) {
        // A late exact-target result is still evidence; it cannot authorize another remote action.
        var operation = owner(operationId, owner, fence);
        require(operation.kind() == Kind.CLEANUP && operation.phase() == Phase.CLAIMED, INVALID_STATE);
        require(upstream != null && vault != null && upstream != Result.PENDING && vault != Result.PENDING, INVALID_STATE);
        var generation = cleanupTarget(operation);
        // shortcut: unresolved refresh blocks final cleanup until its terminal recovery protocol is accepted.
        boolean pendingRefresh = operations.values().stream().anyMatch(value -> value.kind() == Kind.REFRESH
                && value.generationId().equals(generation.id()) && value.phase() != Phase.COMPLETED);
        boolean completed = successful(upstream) && successful(vault) && noLateWritePossible && !pendingRefresh;
        operations.put(operation.id(), copy(operation, completed ? Phase.COMPLETED : Phase.BLOCKED, null,
                upstream, vault, completed ? now : null, now));
    }

    private Generation cleanupTarget(Operation operation) {
        var generation = generation(operation.generationId());
        if (operation.cleanupScope() == Scope.INVALIDATED_GENERATION) {
            require(generation.state() == GenerationState.INVALIDATED, INVALID_STATE);
        } else {
            require(!Objects.equals(generation.activeSecretVersionRef(), operation.cleanupTargetVersionRef())
                    || generation.state() == GenerationState.INVALIDATED, INVALID_STATE);
        }
        return generation;
    }

    private void invalidateCurrent(Instant now) {
        if (root.activeGenerationId() != null) {
            var current = generation(root.activeGenerationId());
            generations.put(current.id(), state(current, GenerationState.INVALIDATED, now));
            cleanup(current, Scope.INVALIDATED_GENERATION, null, now);
        }
    }

    private void terminate(Attempt attempt, AttemptPhase phase, Instant now) {
        var target = generation(attempt.targetGenerationId());
        require(target.state() != GenerationState.ACTIVE, INVALID_STATE);
        attempts.put(attempt.id(), copy(attempt, phase, attempt.consumedAt(), attempt.pkceSecretVersionRef(),
                CleanupState.PENDING, null, now));
        if (target.state() != GenerationState.INVALIDATED) {
            generations.put(target.id(), state(target, GenerationState.INVALIDATED, now));
            cleanup(target, Scope.INVALIDATED_GENERATION, null, now);
        }
    }

    private void cleanup(Generation generation, Scope scope, SecretReference target, Instant now) {
        var id = UUID.randomUUID();
        operations.put(id, new Operation(id, root.id(), generation.id(), root.revision(), generation.revision(),
                Kind.CLEANUP, Phase.PENDING, null, 0, null, null, null, target, scope,
                Result.PENDING, Result.PENDING, null, now, now));
    }

    private Generation current(UUID id, long connectionRevision, long generationRevision) {
        expect(connectionRevision, id);
        var generation = generation(id);
        require(generation.state() == GenerationState.ACTIVE && generation.revision() == generationRevision, CONFLICT);
        return generation;
    }
    private Operation owned(UUID id, UUID owner, long fence, Instant now) {
        var operation = owner(id, owner, fence);
        require(operation.deadlineAt() != null && operation.deadlineAt().isAfter(now), EXPIRED);
        return operation;
    }
    private Operation owner(UUID id, UUID owner, long fence) {
        var operation = operation(id);
        require(owner != null && Objects.equals(operation.ownerId(), owner) && operation.fence() == fence, CONFLICT);
        return operation;
    }
    private void belongs(UUID connectionId) { require(root.id().equals(connectionId), INVALID_STATE); }
    private boolean matches(long revision, UUID active) {
        return root.revision() == revision && Objects.equals(root.activeGenerationId(), active);
    }
    private void expect(long revision, UUID active) { require(matches(revision, active), CONFLICT); }
    private Generation generation(UUID id) {
        var value = generations.get(id); require(value != null, NOT_FOUND); return value;
    }
    private Attempt attempt(UUID id) {
        var value = attempts.get(id); require(value != null, NOT_FOUND); return value;
    }
    private Operation operation(UUID id) {
        var value = operations.get(id); require(value != null, NOT_FOUND); return value;
    }
    private static void require(boolean condition, ConnectionFailure.Reason reason) {
        if (!condition) { throw new ConnectionFailure(reason); }
    }
    private static boolean terminal(AttemptPhase phase) {
        return phase == AttemptPhase.COMPLETED || phase == AttemptPhase.FAILED || phase == AttemptPhase.EXPIRED;
    }
    private static boolean successful(Result result) {
        return result == Result.CONFIRMED || result == Result.NOT_APPLICABLE;
    }
    private static Generation state(Generation value, GenerationState state, Instant now) {
        return new Generation(value.id(), value.connectionId(), value.githubUserId(), state, value.secretObjectRef(),
                value.activeSecretVersionRef(), value.accessExpiresAt(), value.refreshExpiresAt(), value.activatedAt(),
                value.revision() + 1, value.createdAt(), now);
    }
    private static Attempt copy(Attempt value, AttemptPhase phase, Instant consumed, SecretReference version,
                                 CleanupState cleanupState, Result result, Instant now) {
        return new Attempt(value.id(), value.connectionId(), value.identity(), value.expectedConnectionRevision(),
                value.expectedGenerationId(), value.targetGenerationId(), value.stateDigest(), value.expiresAt(),
                phase, consumed, value.pkceSecretObjectRef(), version, cleanupState, result,
                value.approvedReturnRef(), value.correlationId(), value.createdAt(), now);
    }
    private static Operation copy(Operation value, Phase phase, SecretReference candidate, Result upstream,
                                   Result vault, Instant completed, Instant now) {
        return new Operation(value.id(), value.connectionId(), value.generationId(), value.expectedConnectionRevision(),
                value.expectedGenerationRevision(), value.kind(), phase, value.ownerId(), value.fence(), value.startedAt(),
                value.deadlineAt(), candidate, value.cleanupTargetVersionRef(), value.cleanupScope(), upstream, vault,
                completed, value.createdAt(), now);
    }
}
