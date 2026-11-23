package org.pipesor.ingestion.capture.domain.exceptions;

/** Fixed diagnostics only: provider errors and protected evidence never become causes. */
public final class CaptureFailure extends RuntimeException {
    public enum Reason { INVALID_INPUT, CONFLICT, NOT_FOUND, INVALID_STATE, STALE_CLAIM, SCOPE_CHANGED, SOURCE_EXPIRED, UNAVAILABLE }
    private final Reason reason;
    public CaptureFailure(Reason reason) { super(reason.name(), null, false, false); this.reason = reason; }
    public Reason reason() { return reason; }
}
