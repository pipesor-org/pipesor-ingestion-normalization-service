package org.pipesor.ingestion.github.application.commandhandlers;

import java.time.Clock;
import java.util.Objects;
import org.pipesor.ingestion.github.application.commands.RefreshCommand;
import org.pipesor.ingestion.github.application.commands.ConnectionCommands.*;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import static org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure.Reason.*;

/** Exactly one execution attempt, not a recovery runner or an authorization provider. */
public final class RefreshHandler {
    private final GitHubConnections connections;
    private final RefreshSnapshotReader snapshots;
    private final GitHubCredentialCustody custody;
    private final GitHubUserTokens tokens;
    private final Clock clock;
    public RefreshHandler(GitHubConnections connections, RefreshSnapshotReader snapshots,
            GitHubCredentialCustody custody, GitHubUserTokens tokens, Clock clock) {
        this.connections = Objects.requireNonNull(connections); this.snapshots = Objects.requireNonNull(snapshots);
        this.custody = Objects.requireNonNull(custody); this.tokens = Objects.requireNonNull(tokens); this.clock = Objects.requireNonNull(clock);
    }
    public Outcome handle(RefreshCommand c) {
        Objects.requireNonNull(c);
        var owner = new RefreshOwner(c.connectionId(), c.operationId(), c.ownerId(), 1);
        boolean claimed = false;
        try {
            validate(c, snapshots.read(c.connectionId(), null));
            deadline(c);
            connections.claimRefresh(new ClaimRefresh(c.connectionId(), c.operationId(), c.generationId(),
                    c.connectionRevision(), c.generationRevision(), c.ownerId(), c.deadlineAt()));
            claimed = true;
            var snapshot = snapshots.read(c.connectionId(), c.operationId());
            validate(c, snapshot);
            var operation = snapshot.operation();
            require(operation != null && operation.connectionId().equals(c.connectionId())
                    && operation.generationId().equals(c.generationId()) && operation.id().equals(c.operationId())
                    && operation.ownerId().equals(c.ownerId()) && operation.fence() == 1
                    && operation.kind() == Kind.REFRESH && operation.phase() == Phase.CLAIMED
                    && operation.expectedConnectionRevision() == c.connectionRevision()
                    && operation.expectedGenerationRevision() == c.generationRevision()
                    && operation.deadlineAt().equals(c.deadlineAt()), CONFLICT);
            var generation = snapshot.generation();
            var old = custody.readPair(new SavedVersion(generation.secretObjectRef(), generation.activeSecretVersionRef()));
            require(storedTime(old.accessExpiresAt()).equals(generation.accessExpiresAt())
                    && storedTime(old.refreshExpiresAt()).equals(generation.refreshExpiresAt()), CONFLICT);
            require(old.refreshExpiresAt().isAfter(clock.instant()), EXPIRED);
            deadline(c);
            connections.requestMayBeInFlight(owner);
            deadline(c);
            require(old.refreshExpiresAt().isAfter(clock.instant()), EXPIRED);
            var replacement = tokens.refresh(old.refreshToken());
            // Preserve a late returned pair even after expiry/relink; only guarded metadata can activate it.
            var candidate = custody.savePair(generation.secretObjectRef(), replacement);
            connections.saveRefreshVersion(new SaveRefreshVersion(c.connectionId(), c.operationId(), c.ownerId(), 1, candidate));
            if (!c.deadlineAt().isAfter(clock.instant())) {
                connections.blockRefresh(owner); return Outcome.EXPIRED;
            }
            var outcome = connections.activateRefresh(new ActivateRefresh(c.connectionId(), c.operationId(), c.ownerId(), 1,
                    replacement.accessExpiresAt(), replacement.refreshExpiresAt()));
            if (outcome != Outcome.ACTIVATED) { connections.blockRefresh(owner); }
            return outcome;
        } catch (RuntimeException failure) {
            if (claimed) { try { connections.blockRefresh(owner); } catch (RuntimeException unavailable) { /* Nonterminal claim still excludes another refresh. */ } }
            if (failure instanceof GitHubUserTokens.Failure provider && provider.reason() == GitHubUserTokens.Failure.Reason.REJECTED) {
                throw new GitHubAccessFailure(GitHubAccessFailure.Reason.CONNECTION_REQUIRED);
            }
            if (failure instanceof ConnectionFailure metadata) { throw metadata; }
            throw new ConnectionFailure(UNAVAILABLE);
        }
    }
    private void validate(RefreshCommand c, RefreshSnapshotReader.Snapshot s) {
        require(s != null && s.root() != null && s.generation() != null, CONFLICT);
        var r = s.root(); var g = s.generation();
        require(c.connectionId().equals(r.id()) && c.identity().equals(r.identity()) && r.revision() == c.connectionRevision()
                && c.generationId().equals(r.activeGenerationId()) && c.generationId().equals(g.id())
                && c.connectionId().equals(g.connectionId()) && g.revision() == c.generationRevision()
                && g.state() == GenerationState.ACTIVE, CONFLICT);
    }
    // PostgreSQL timestamptz rounds expiry metadata; actual use below retains the exact provider expiry.
    private static java.time.Instant storedTime(java.time.Instant value) {
        return value.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
    private void deadline(RefreshCommand c) { require(c.deadlineAt().isAfter(clock.instant()), EXPIRED); }
    private static void require(boolean valid, ConnectionFailure.Reason reason) { if (!valid) { throw new ConnectionFailure(reason); } }
}
