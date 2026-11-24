package org.pipesor.ingestion.auth.application.services;

/** Fixed login outcomes; provider messages, codes and tokens never cross this boundary. */
public final class LoginFailure extends RuntimeException {
    public enum Reason { INVALID_REQUEST, CONSENT_REQUIRED, INVALID_STATE, ACCESS_DENIED, INVALID_HANDOFF, UNAVAILABLE }

    private final Reason reason;

    public LoginFailure(Reason reason) {
        super("GitHub login " + reason.name().toLowerCase(java.util.Locale.ROOT), null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
