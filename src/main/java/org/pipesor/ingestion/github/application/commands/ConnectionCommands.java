package org.pipesor.ingestion.github.application.commands;

import java.time.Instant;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.Result;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;

/**
 * Local commands from trusted executors, never HTTP resources. Saved-version and no-late-write signals
 * require adapter evidence; constructing a command does not establish authentication or that evidence.
 */
public final class ConnectionCommands {
    private ConnectionCommands() {}
    public record Create(UUID connectionId, ConnectionIdentity identity) {}
    public record Prepare(UUID connectionId, UUID attemptId, UUID generationId, long expectedRevision,
                          UUID expectedGenerationId, StateDigest stateDigest, Instant expiresAt,
                          SecretReference credentialObject, SecretReference pkceObject,
                          String approvedReturnRef, String correlationId) {}
    public record Ready(UUID connectionId, UUID attemptId, SavedVersion pkceVersion) {}
    public record Claim(UUID connectionId, UUID attemptId, ConnectionIdentity identity, StateDigest digest) {}
    public record Activate(UUID connectionId, UUID attemptId, String verifiedGitHubUserId,
                           SavedVersion credentialVersion, Instant accessExpiresAt, Instant refreshExpiresAt) {}
    public record Abandon(UUID connectionId, UUID attemptId, boolean expired) {}
    public record FinishPkceCleanup(UUID connectionId, UUID attemptId, Result result, boolean noLateWritePossible) {}
    public record ClaimRefresh(UUID connectionId, UUID operationId, UUID generationId,
                               long expectedConnectionRevision, long expectedGenerationRevision,
                               UUID ownerId, Instant deadlineAt) {}
    public record RefreshOwner(UUID connectionId, UUID operationId, UUID ownerId, long fence) {}
    public record SaveRefreshVersion(UUID connectionId, UUID operationId, UUID ownerId,
                                     long fence, SavedVersion candidate) {}
    public record ActivateRefresh(UUID connectionId, UUID operationId, UUID ownerId, long fence,
                                   Instant accessExpiresAt, Instant refreshExpiresAt) {}
    public record Disconnect(UUID connectionId, long expectedRevision) {}
    public record ClaimCleanup(UUID connectionId, UUID operationId, UUID ownerId, Instant deadlineAt) {}
    public record FinishCleanup(UUID connectionId, UUID operationId, UUID ownerId, long fence,
                                 Result upstreamResult, Result vaultResult, boolean noLateWritePossible) {}
}
