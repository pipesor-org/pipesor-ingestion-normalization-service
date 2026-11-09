package org.pipesor.ingestion.github.application.services;

import org.pipesor.ingestion.github.application.commands.ConnectionCommands.*;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;

public interface GitHubConnections {
    void create(Create command);
    void prepare(Prepare command);
    void ready(Ready command);
    Outcome claim(Claim command);
    Outcome activate(Activate command);
    void abandon(Abandon command);
    void finishPkceCleanup(FinishPkceCleanup command);
    void claimRefresh(ClaimRefresh command);
    void requestMayBeInFlight(RefreshOwner command);
    void saveRefreshVersion(SaveRefreshVersion command);
    Outcome activateRefresh(ActivateRefresh command);
    void blockRefresh(RefreshOwner command);
    void disconnect(Disconnect command);
    void claimCleanup(ClaimCleanup command);
    void finishCleanup(FinishCleanup command);
}
