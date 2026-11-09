package org.pipesor.ingestion.github.domain.model.entities;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.StateDigest;

/** Immutable lifecycle snapshots of entities owned and changed through GitHubConnection. */
public final class ConnectionRecords {
    private ConnectionRecords() {}
    public enum GenerationState { PREPARING, ACTIVE, BLOCKED, INVALIDATED }
    public enum AttemptPhase { PREPARING, READY, CLAIMED, COMPLETED, FAILED, EXPIRED }
    public enum CleanupState { HELD, PENDING, COMPLETED, BLOCKED }
    public enum Result { PENDING, CONFIRMED, UNAVAILABLE, SCOPE_UNAPPROVED, NOT_APPLICABLE }
    public enum Kind { REFRESH, CLEANUP }
    public enum Phase { PENDING, CLAIMED, REQUEST_MAY_BE_IN_FLIGHT, VERSION_SAVED, COMPLETED, BLOCKED }
    public enum Scope { EXACT_VERSION, INVALIDATED_GENERATION }

    public record Root(UUID id, ConnectionIdentity identity, long revision, UUID activeGenerationId,
                       Instant createdAt, Instant updatedAt) {}
    public record Generation(UUID id, UUID connectionId, String githubUserId, GenerationState state,
                             SecretReference secretObjectRef, SecretReference activeSecretVersionRef,
                             Instant accessExpiresAt, Instant refreshExpiresAt, Instant activatedAt,
                             long revision, Instant createdAt, Instant updatedAt) {}
    public record Attempt(UUID id, UUID connectionId, ConnectionIdentity identity,
                          long expectedConnectionRevision, UUID expectedGenerationId, UUID targetGenerationId,
                          StateDigest stateDigest, Instant expiresAt, AttemptPhase phase, Instant consumedAt,
                          SecretReference pkceSecretObjectRef, SecretReference pkceSecretVersionRef,
                          CleanupState pkceCleanupState, Result pkceCleanupResult,
                          String approvedReturnRef, String correlationId, Instant createdAt, Instant updatedAt) {}
    public record Operation(UUID id, UUID connectionId, UUID generationId,
                            long expectedConnectionRevision, long expectedGenerationRevision,
                            Kind kind, Phase phase, UUID ownerId, long fence, Instant startedAt, Instant deadlineAt,
                            SecretReference candidateSecretVersionRef, SecretReference cleanupTargetVersionRef,
                            Scope cleanupScope, Result upstreamResult, Result vaultResult, Instant completedAt,
                            Instant createdAt, Instant updatedAt) {}
}
