package org.pipesor.ingestion.github.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.shared.infrastructure.persistence.jpa.JpaMutableMetadata;

/** Flat mappings: no cross-context associations and no private-reference toString. */
public final class ConnectionJpaEntities {
    private ConnectionJpaEntities() {}
    private static SecretReference reference(String value) { return value == null ? null : new SecretReference(value); }
    private static String value(SecretReference reference) { return reference == null ? null : reference.value(); }

    @Entity(name = "GitHubRootRow")
    @Table(name = "github_connections", schema = "ingestion")
    public static class RootRow extends JpaMutableMetadata {
        public RootRow() {}
        @Column(name = "tenant_id", nullable = false)
        UUID tenantId;
        @Column(name = "entra_object_id", nullable = false)
        UUID entraObjectId;
        @Column(name = "revision", nullable = false)
        long revision;
        @Column(name = "active_generation_id", nullable = true)
        UUID activeGenerationId;

        Root snapshot() {
            return new Root(id, new ConnectionIdentity(tenantId, entraObjectId), revision, activeGenerationId, createdAt, updatedAt);
        }

        void apply(Root value) {
            metadata(value.id(), value.createdAt(), value.updatedAt());
            tenantId = value.identity().tenantId();
            entraObjectId = value.identity().objectId();
            revision = value.revision();
            activeGenerationId = value.activeGenerationId();
        }
    }

    @Entity(name = "GitHubGenerationRow")
    @Table(name = "github_connection_generations", schema = "ingestion")
    public static class GenerationRow extends JpaMutableMetadata {
        public GenerationRow() {}
        @Column(name = "connection_id", nullable = false)
        UUID connectionId;
        @Column(name = "github_user_id", nullable = true, columnDefinition = "text")
        String githubUserId;
        @Enumerated(EnumType.STRING)
        @Column(name = "state", nullable = false, columnDefinition = "text")
        GenerationState state;
        @Column(name = "secret_object_ref", nullable = false, columnDefinition = "text")
        String secretObjectRef;
        @Column(name = "active_secret_version_ref", nullable = true, columnDefinition = "text")
        String activeSecretVersionRef;
        @Column(name = "access_expires_at", nullable = true)
        Instant accessExpiresAt;
        @Column(name = "refresh_expires_at", nullable = true)
        Instant refreshExpiresAt;
        @Column(name = "activated_at", nullable = true)
        Instant activatedAt;
        @Column(name = "revision", nullable = false)
        long revision;

        Generation snapshot() {
            return new Generation(id, connectionId, githubUserId, state, reference(secretObjectRef), reference(activeSecretVersionRef), accessExpiresAt, refreshExpiresAt, activatedAt, revision, createdAt, updatedAt);
        }

        void apply(Generation value) {
            metadata(value.id(), value.createdAt(), value.updatedAt());
            connectionId = value.connectionId();
            githubUserId = value.githubUserId();
            state = value.state();
            secretObjectRef = value(value.secretObjectRef());
            activeSecretVersionRef = value(value.activeSecretVersionRef());
            accessExpiresAt = value.accessExpiresAt();
            refreshExpiresAt = value.refreshExpiresAt();
            activatedAt = value.activatedAt();
            revision = value.revision();
        }
    }

    @Entity(name = "GitHubAttemptRow")
    @Table(name = "github_authorization_attempts", schema = "ingestion")
    public static class AttemptRow extends JpaMutableMetadata {
        public AttemptRow() {}
        @Column(name = "connection_id", nullable = false)
        UUID connectionId;
        @Column(name = "tenant_id", nullable = false)
        UUID tenantId;
        @Column(name = "entra_object_id", nullable = false)
        UUID entraObjectId;
        @Column(name = "expected_connection_revision", nullable = false)
        long expectedConnectionRevision;
        @Column(name = "expected_generation_id", nullable = true)
        UUID expectedGenerationId;
        @Column(name = "target_generation_id", nullable = false)
        UUID targetGenerationId;
        @Column(name = "state_digest", nullable = false, columnDefinition = "bytea")
        byte[] stateDigest;
        @Column(name = "expires_at", nullable = false)
        Instant expiresAt;
        @Enumerated(EnumType.STRING)
        @Column(name = "phase", nullable = false, columnDefinition = "text")
        AttemptPhase phase;
        @Column(name = "consumed_at", nullable = true)
        Instant consumedAt;
        @Column(name = "pkce_secret_object_ref", nullable = false, columnDefinition = "text")
        String pkceSecretObjectRef;
        @Column(name = "pkce_secret_version_ref", nullable = true, columnDefinition = "text")
        String pkceSecretVersionRef;
        @Enumerated(EnumType.STRING)
        @Column(name = "pkce_cleanup_state", nullable = false, columnDefinition = "text")
        CleanupState pkceCleanupState;
        @Enumerated(EnumType.STRING)
        @Column(name = "pkce_cleanup_result", nullable = true, columnDefinition = "text")
        Result pkceCleanupResult;
        @Column(name = "approved_return_ref", nullable = false, columnDefinition = "text")
        String approvedReturnRef;
        @Column(name = "correlation_id", nullable = false, columnDefinition = "text")
        String correlationId;

        Attempt snapshot() {
            return new Attempt(id, connectionId, new ConnectionIdentity(tenantId, entraObjectId), expectedConnectionRevision, expectedGenerationId, targetGenerationId, new StateDigest(stateDigest), expiresAt, phase, consumedAt, reference(pkceSecretObjectRef), reference(pkceSecretVersionRef), pkceCleanupState, pkceCleanupResult, approvedReturnRef, correlationId, createdAt, updatedAt);
        }

        void apply(Attempt value) {
            metadata(value.id(), value.createdAt(), value.updatedAt());
            connectionId = value.connectionId();
            tenantId = value.identity().tenantId();
            entraObjectId = value.identity().objectId();
            expectedConnectionRevision = value.expectedConnectionRevision();
            expectedGenerationId = value.expectedGenerationId();
            targetGenerationId = value.targetGenerationId();
            stateDigest = value.stateDigest().value();
            expiresAt = value.expiresAt();
            phase = value.phase();
            consumedAt = value.consumedAt();
            pkceSecretObjectRef = value(value.pkceSecretObjectRef());
            pkceSecretVersionRef = value(value.pkceSecretVersionRef());
            pkceCleanupState = value.pkceCleanupState();
            pkceCleanupResult = value.pkceCleanupResult();
            approvedReturnRef = value.approvedReturnRef();
            correlationId = value.correlationId();
        }
    }

    @Entity(name = "GitHubOperationRow")
    @Table(name = "github_credential_operations", schema = "ingestion")
    public static class OperationRow extends JpaMutableMetadata {
        public OperationRow() {}
        @Column(name = "connection_id", nullable = false)
        UUID connectionId;
        @Column(name = "generation_id", nullable = false)
        UUID generationId;
        @Column(name = "expected_connection_revision", nullable = false)
        long expectedConnectionRevision;
        @Column(name = "expected_generation_revision", nullable = false)
        long expectedGenerationRevision;
        @Enumerated(EnumType.STRING)
        @Column(name = "kind", nullable = false, columnDefinition = "text")
        Kind kind;
        @Enumerated(EnumType.STRING)
        @Column(name = "phase", nullable = false, columnDefinition = "text")
        Phase phase;
        @Column(name = "owner_id", nullable = true)
        UUID ownerId;
        @Column(name = "fence", nullable = false)
        long fence;
        @Column(name = "started_at", nullable = true)
        Instant startedAt;
        @Column(name = "deadline_at", nullable = true)
        Instant deadlineAt;
        @Column(name = "candidate_secret_version_ref", nullable = true, columnDefinition = "text")
        String candidateSecretVersionRef;
        @Column(name = "cleanup_target_version_ref", nullable = true, columnDefinition = "text")
        String cleanupTargetVersionRef;
        @Enumerated(EnumType.STRING)
        @Column(name = "cleanup_scope", nullable = true, columnDefinition = "text")
        Scope cleanupScope;
        @Enumerated(EnumType.STRING)
        @Column(name = "upstream_result", nullable = true, columnDefinition = "text")
        Result upstreamResult;
        @Enumerated(EnumType.STRING)
        @Column(name = "vault_result", nullable = true, columnDefinition = "text")
        Result vaultResult;
        @Column(name = "completed_at", nullable = true)
        Instant completedAt;

        Operation snapshot() {
            return new Operation(id, connectionId, generationId, expectedConnectionRevision, expectedGenerationRevision, kind, phase, ownerId, fence, startedAt, deadlineAt, reference(candidateSecretVersionRef), reference(cleanupTargetVersionRef), cleanupScope, upstreamResult, vaultResult, completedAt, createdAt, updatedAt);
        }

        void apply(Operation value) {
            metadata(value.id(), value.createdAt(), value.updatedAt());
            connectionId = value.connectionId();
            generationId = value.generationId();
            expectedConnectionRevision = value.expectedConnectionRevision();
            expectedGenerationRevision = value.expectedGenerationRevision();
            kind = value.kind();
            phase = value.phase();
            ownerId = value.ownerId();
            fence = value.fence();
            startedAt = value.startedAt();
            deadlineAt = value.deadlineAt();
            candidateSecretVersionRef = value(value.candidateSecretVersionRef());
            cleanupTargetVersionRef = value(value.cleanupTargetVersionRef());
            cleanupScope = value.cleanupScope();
            upstreamResult = value.upstreamResult();
            vaultResult = value.vaultResult();
            completedAt = value.completedAt();
        }
    }
}
