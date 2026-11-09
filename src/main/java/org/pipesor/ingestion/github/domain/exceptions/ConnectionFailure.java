package org.pipesor.ingestion.github.domain.exceptions;

/** Fixed diagnostics: no provider, database or private custody metadata escapes through causes. */
public final class ConnectionFailure extends RuntimeException {
    public enum Reason { NOT_FOUND, CONFLICT, EXPIRED, INVALID_STATE, UNAVAILABLE }
    private final Reason reason;
    public ConnectionFailure(Reason reason) {
        super("GitHub connection metadata " + reason.name().toLowerCase(java.util.Locale.ROOT), null, false, false);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
