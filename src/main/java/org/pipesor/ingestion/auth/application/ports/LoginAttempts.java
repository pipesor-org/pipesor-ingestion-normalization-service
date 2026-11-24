package org.pipesor.ingestion.auth.application.ports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.pipesor.ingestion.github.domain.model.valueobjects.StateDigest;

/**
 * One-time browser login attempts (platform identity contract v1, §5). Each transition is a single
 * atomic conditional write; raw state and handoff values are never stored, only their digests.
 */
public interface LoginAttempts {
    void create(NewAttempt attempt);
    /** READY and unexpired → CLAIMED, exactly once. */
    Optional<Claimed> claim(StateDigest state, Instant now);
    /** CLAIMED → FAILED; idempotent for already terminal attempts. */
    void fail(UUID attemptId, Instant now);
    /** CLAIMED → HANDOFF_READY with the verified link. */
    void offerHandoff(UUID attemptId, Handoff handoff, Instant now);
    /** HANDOFF_READY and unexpired → COMPLETED, exactly once. */
    Optional<Completed> consumeHandoff(StateDigest handoff, Instant now);

    /** {@code consentVersion} is the accepted documents version; {@code createdAt} is also the acceptance time. */
    record NewAttempt(UUID id, StateDigest state, SavedVersion verifier, String returnPath, Instant expiresAt,
                      String correlationId, Instant createdAt, LocalDate consentVersion) {
        @Override public String toString() { return "LoginAttempt[private]"; }
    }
    /** {@code consentVersion} is null only for attempts created before consent was recorded. */
    record Claimed(UUID id, SavedVersion verifier, String returnPath, Instant expiresAt, String correlationId,
                   Instant createdAt, LocalDate consentVersion) {
        @Override public String toString() { return "ClaimedLogin[private]"; }
    }
    record Handoff(StateDigest digest, Instant expiresAt, UUID connectionId, UUID generationId, String githubUserId,
                   String login, String returnPath) {
        @Override public String toString() { return "LoginHandoff[private]"; }
    }
    record Completed(UUID attemptId, Handoff handoff) {
        @Override public String toString() { return "CompletedLogin[private]"; }
    }
}
