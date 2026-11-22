package org.pipesor.ingestion.github.infrastructure.provider;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.github.application.commandhandlers.RefreshHandler;
import org.pipesor.ingestion.github.application.commands.RefreshCommand;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody;
import org.pipesor.ingestion.github.application.ports.Observation;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub.Identity;
import org.pipesor.ingestion.github.application.ports.RefreshSnapshotReader;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.GenerationState;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;
import org.pipesor.ingestion.github.domain.model.valueobjects.GitHubLink;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.pipesor.ingestion.shared.security.PlatformIds;

import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.*;

/**
 * The current GitHub link of a session subject, read from the ADR-020 connection metadata and the
 * credential custody. An access token close to expiry is refreshed once through {@link RefreshHandler}
 * before the link is returned, so a single query observes one stable link revision.
 */
public final class ConnectedGitHubLink implements CurrentGitHubLink {
    private static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);
    private static final Duration REFRESH_BUDGET = Duration.ofSeconds(20);
    private final ConnectionStatusReader statuses;
    private final RefreshSnapshotReader snapshots;
    private final GitHubCredentialCustody custody;
    private final RefreshHandler refresh;
    private final Clock clock;

    public ConnectedGitHubLink(ConnectionStatusReader statuses, RefreshSnapshotReader snapshots,
            GitHubCredentialCustody custody, RefreshHandler refresh, Clock clock) {
        this.statuses = Objects.requireNonNull(statuses); this.snapshots = Objects.requireNonNull(snapshots);
        this.custody = Objects.requireNonNull(custody); this.refresh = Objects.requireNonNull(refresh);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Observation<AccessContext, Optional<GitHubLink>> read(AccessContext context) {
        var identity = identity(context);
        try {
            var current = current(identity);
            if (current.isPresent() && current.get().generation().accessExpiresAt().isBefore(clock.instant().plus(REFRESH_BEFORE))) {
                current = refreshed(identity, current.get());
            }
            return new Observation<>(context, current.map(snapshot -> link(context, snapshot)), clock.instant());
        } catch (GitHubAccessFailure fixed) { throw fixed; }
        catch (RuntimeException unavailable) { throw new GitHubAccessFailure(UNAVAILABLE); }
    }

    /** The user credential of exactly the link generation and revision the query observed. */
    public GitHubReadProvider.Credential credential(Identity identity) {
        try {
            var snapshot = current(identity(identity.context())).orElseThrow(() -> new GitHubAccessFailure(CONNECTION_REQUIRED));
            if (!snapshot.generation().id().equals(identity.link().generationId())
                    || !Long.toString(snapshot.root().revision()).equals(identity.link().revision())) {
                throw new GitHubAccessFailure(CONNECTION_REQUIRED);
            }
            var generation = snapshot.generation();
            var pair = custody.readPair(new SavedVersion(generation.secretObjectRef(), generation.activeSecretVersionRef()));
            return new GitHubReadProvider.Credential(identity, pair.accessToken(), pair.accessExpiresAt());
        } catch (GitHubAccessFailure fixed) { throw fixed; }
        catch (RuntimeException unavailable) { throw new GitHubAccessFailure(UNAVAILABLE); }
    }

    private Optional<RefreshSnapshotReader.Snapshot> current(ConnectionIdentity identity) {
        var status = statuses.find(identity);
        if (status.isEmpty() || status.get().activeGenerationId() == null) { return Optional.empty(); }
        var snapshot = snapshots.read(status.get().connectionId(), null);
        var generation = snapshot.generation();
        if (generation == null || generation.state() != GenerationState.ACTIVE || generation.githubUserId() == null
                || !snapshot.root().identity().equals(identity)
                // The session subject is derived from the GitHub user id; the stored link must belong to it.
                || !PlatformIds.human(Long.parseLong(generation.githubUserId())).equals(identity.objectId())) {
            return Optional.empty();
        }
        if (!generation.refreshExpiresAt().isAfter(clock.instant())) { return Optional.empty(); }
        return Optional.of(snapshot);
    }

    private Optional<RefreshSnapshotReader.Snapshot> refreshed(ConnectionIdentity identity, RefreshSnapshotReader.Snapshot snapshot) {
        var root = snapshot.root();
        var generation = snapshot.generation();
        try {
            var outcome = refresh.handle(new RefreshCommand(root.id(), identity, generation.id(), root.revision(),
                    generation.revision(), UUID.randomUUID(), UUID.randomUUID(), clock.instant().plus(REFRESH_BUDGET)));
            if (outcome != Outcome.ACTIVATED) { throw new GitHubAccessFailure(UNAVAILABLE); }
        } catch (ConnectionFailure failure) {
            if (failure.reason() == ConnectionFailure.Reason.EXPIRED) { return Optional.empty(); }
            if (failure.reason() != ConnectionFailure.Reason.CONFLICT) { throw new GitHubAccessFailure(UNAVAILABLE); }
            // A concurrent request may have refreshed the same generation; use it only if it is fresh now.
        }
        var after = current(identity);
        if (after.isPresent() && after.get().generation().accessExpiresAt().isBefore(clock.instant().plus(Duration.ofSeconds(30)))) {
            throw new GitHubAccessFailure(UNAVAILABLE);
        }
        return after;
    }

    private static GitHubLink link(AccessContext context, RefreshSnapshotReader.Snapshot snapshot) {
        var generation = snapshot.generation();
        return new GitHubLink(context.tenantId(), context.subjectRef(), generation.githubUserId(),
                Long.toString(snapshot.root().revision()), generation.activatedAt(), generation.id());
    }

    private static ConnectionIdentity identity(AccessContext context) {
        try {
            var parts = context.subjectRef().split(":", -1);
            var tenant = UUID.fromString(context.tenantId());
            if (parts.length != 2 || !tenant.equals(UUID.fromString(parts[0]))) { throw new IllegalArgumentException(); }
            return new ConnectionIdentity(tenant, UUID.fromString(parts[1]));
        } catch (RuntimeException invalid) { throw new GitHubAccessFailure(UNAVAILABLE); }
    }

    @Override public String toString() { return "ConnectedGitHubLink[configured]"; }
}
