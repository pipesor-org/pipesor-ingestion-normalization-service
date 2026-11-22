package org.pipesor.ingestion.auth.application.commands;

import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;

/** Login commands built by the HTTP boundary; constructing them authenticates nothing. */
public final class LoginCommands {
    private LoginCommands() { }
    public record Start(String returnPath, String correlationId) { }
    public record Callback(String code, String state, String error, String correlationId) {
        @Override public String toString() { return "Callback[private]"; }
    }
    public record Exchange(String handoff) {
        @Override public String toString() { return "Exchange[private]"; }
    }
    public record Logout(ConnectionIdentity identity) { }
}
