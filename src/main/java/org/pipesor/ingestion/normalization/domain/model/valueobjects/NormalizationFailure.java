package org.pipesor.ingestion.normalization.domain.model.valueobjects;

/** Fixed diagnostics: rejected evidence and dependency causes never leave this boundary. */
public final class NormalizationFailure extends RuntimeException {
    public enum Reason {
        INVALID_IDENTITY, INVALID_CONTEXT, INVALID_TIME, INVALID_STATUS, INVALID_STAGE,
        INVALID_EVIDENCE, SCOPE_MISMATCH, PROTECTION_REFUSED, PROTECTION_UNAVAILABLE,
        INVALID_PROTECTION_RESULT, SERIALIZATION_FAILED
    }

    private final Reason reason;

    public NormalizationFailure(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
