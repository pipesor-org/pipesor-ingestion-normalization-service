package org.pipesor.ingestion.github.application.commandhandlers;

import java.time.Clock;
import org.pipesor.ingestion.github.application.commands.ConnectionCommands.*;
import org.pipesor.ingestion.github.application.ports.ConnectionRepository;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;

/** The infrastructure transaction must encompass load, domain transition and every persistence write. */
public final class GitHubConnectionHandler implements GitHubConnections {
    private final ConnectionRepository repository;
    private final Clock clock;
    public GitHubConnectionHandler(ConnectionRepository repository, Clock clock) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.clock = java.util.Objects.requireNonNull(clock);
    }
    @Override public void create(Create c) { repository.insert(GitHubConnection.create(c.connectionId(), c.identity(), clock.instant())); }
    @Override public void prepare(Prepare c) {
        var root = repository.connection(c.connectionId());
        root.prepare(c.attemptId(), c.generationId(), c.expectedRevision(), c.expectedGenerationId(), c.stateDigest(),
                c.expiresAt(), c.credentialObject(), c.pkceObject(), c.approvedReturnRef(), c.correlationId(), clock.instant());
        repository.save(root);
    }
    @Override public void ready(Ready c) {
        var root = repository.attempt(c.connectionId(), c.attemptId());
        root.ready(c.attemptId(), c.pkceVersion(), clock.instant()); repository.save(root);
    }
    @Override public Outcome claim(Claim c) {
        var root = repository.attempt(c.connectionId(), c.attemptId());
        var result = root.claim(c.attemptId(), c.identity(), c.digest(), clock.instant()); repository.save(root); return result;
    }
    @Override public Outcome activate(Activate c) {
        var root = repository.attempt(c.connectionId(), c.attemptId());
        var result = root.activate(c.attemptId(), c.verifiedGitHubUserId(), c.credentialVersion(),
                c.accessExpiresAt(), c.refreshExpiresAt(), clock.instant()); repository.save(root); return result;
    }
    @Override public void abandon(Abandon c) {
        var root = repository.attempt(c.connectionId(), c.attemptId());
        root.abandon(c.attemptId(), c.expired(), clock.instant()); repository.save(root);
    }
    @Override public void finishPkceCleanup(FinishPkceCleanup c) {
        var root = repository.attempt(c.connectionId(), c.attemptId());
        root.finishPkceCleanup(c.attemptId(), c.result(), c.noLateWritePossible(), clock.instant()); repository.save(root);
    }
    @Override public void claimRefresh(ClaimRefresh c) {
        var root = repository.generation(c.connectionId(), c.generationId());
        root.claimRefresh(c.operationId(), c.generationId(), c.expectedConnectionRevision(), c.expectedGenerationRevision(),
                c.ownerId(), c.deadlineAt(), clock.instant()); repository.save(root);
    }
    @Override public void requestMayBeInFlight(RefreshOwner c) {
        var root = repository.operation(c.connectionId(), c.operationId());
        root.requestMayBeInFlight(c.operationId(), c.ownerId(), c.fence(), clock.instant()); repository.save(root);
    }
    @Override public void saveRefreshVersion(SaveRefreshVersion c) {
        var root = repository.operation(c.connectionId(), c.operationId());
        root.saveRefreshVersion(c.operationId(), c.ownerId(), c.fence(), c.candidate(), clock.instant()); repository.save(root);
    }
    @Override public Outcome activateRefresh(ActivateRefresh c) {
        var root = repository.operation(c.connectionId(), c.operationId());
        var result = root.activateRefresh(c.operationId(), c.ownerId(), c.fence(), c.accessExpiresAt(),
                c.refreshExpiresAt(), clock.instant()); repository.save(root); return result;
    }
    @Override public void blockRefresh(RefreshOwner c) {
        var root = repository.operation(c.connectionId(), c.operationId());
        root.blockRefresh(c.operationId(), c.ownerId(), c.fence(), clock.instant()); repository.save(root);
    }
    @Override public void disconnect(Disconnect c) {
        var root = repository.connectionForDisconnect(c.connectionId());
        root.disconnect(c.expectedRevision(), clock.instant()); repository.save(root);
    }
    @Override public void claimCleanup(ClaimCleanup c) {
        var root = repository.operation(c.connectionId(), c.operationId());
        root.claimCleanup(c.operationId(), c.ownerId(), c.deadlineAt(), clock.instant()); repository.save(root);
    }
    @Override public void finishCleanup(FinishCleanup c) {
        var root = repository.operation(c.connectionId(), c.operationId());
        root.finishCleanup(c.operationId(), c.ownerId(), c.fence(), c.upstreamResult(), c.vaultResult(),
                c.noLateWritePossible(), clock.instant()); repository.save(root);
    }
}
