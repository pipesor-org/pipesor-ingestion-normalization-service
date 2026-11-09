package org.pipesor.ingestion.github.infrastructure.persistence.jpa;

import java.util.function.Supplier;
import org.pipesor.ingestion.github.application.commands.ConnectionCommands.*;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Each call commits all metadata before returning; no supplied callback can perform remote I/O. */
public final class TransactionalGitHubConnections implements GitHubConnections {
    private final GitHubConnections delegate;
    private final TransactionTemplate transaction;
    public TransactionalGitHubConnections(GitHubConnections delegate, PlatformTransactionManager manager) {
        this.delegate = delegate;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private <T> T execute(Supplier<T> action) {
        try { return transaction.execute(status -> action.get()); }
        catch (ConnectionFailure failure) { throw failure; }
        catch (RuntimeException failure) {
            // Database errors may include failing rows and private references; expose no cause or provider text.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw new ConnectionFailure(ConnectionFailure.Reason.CONFLICT);
                }
            }
            throw new ConnectionFailure(ConnectionFailure.Reason.UNAVAILABLE);
        }
    }
    @Override public void create(Create command) { execute(() -> { delegate.create(command); return null; }); }
    @Override public void prepare(Prepare command) { execute(() -> { delegate.prepare(command); return null; }); }
    @Override public void ready(Ready command) { execute(() -> { delegate.ready(command); return null; }); }
    @Override public Outcome claim(Claim command) { return execute(() -> delegate.claim(command)); }
    @Override public Outcome activate(Activate command) { return execute(() -> delegate.activate(command)); }
    @Override public void abandon(Abandon command) { execute(() -> { delegate.abandon(command); return null; }); }
    @Override public void finishPkceCleanup(FinishPkceCleanup command) { execute(() -> { delegate.finishPkceCleanup(command); return null; }); }
    @Override public void claimRefresh(ClaimRefresh command) { execute(() -> { delegate.claimRefresh(command); return null; }); }
    @Override public void requestMayBeInFlight(RefreshOwner command) { execute(() -> { delegate.requestMayBeInFlight(command); return null; }); }
    @Override public void saveRefreshVersion(SaveRefreshVersion command) { execute(() -> { delegate.saveRefreshVersion(command); return null; }); }
    @Override public Outcome activateRefresh(ActivateRefresh command) { return execute(() -> delegate.activateRefresh(command)); }
    @Override public void blockRefresh(RefreshOwner command) { execute(() -> { delegate.blockRefresh(command); return null; }); }
    @Override public void disconnect(Disconnect command) { execute(() -> { delegate.disconnect(command); return null; }); }
    @Override public void claimCleanup(ClaimCleanup command) { execute(() -> { delegate.claimCleanup(command); return null; }); }
    @Override public void finishCleanup(FinishCleanup command) { execute(() -> { delegate.finishCleanup(command); return null; }); }
}
