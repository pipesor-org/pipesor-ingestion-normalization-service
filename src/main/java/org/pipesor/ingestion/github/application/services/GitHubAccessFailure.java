package org.pipesor.ingestion.github.application.services;

/** Fixed diagnostics only. Provider messages and causes must never cross this boundary. */
public final class GitHubAccessFailure extends RuntimeException {
    public enum Reason { CONNECTION_REQUIRED, DENIED, UNAVAILABLE }

    private final Reason reason;

    public GitHubAccessFailure(Reason reason) {
        super(switch (reason) {
            case CONNECTION_REQUIRED -> "GitHub connection required";
            case DENIED -> "GitHub access denied";
            case UNAVAILABLE -> "GitHub verification unavailable";
        }, null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
