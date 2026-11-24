package org.pipesor.ingestion.auth.application.ports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;

/**
 * Append-only proof of the consent given at sign-in (ADR-029). Rows are only appended; revocation
 * is a manual operator procedure, so this port never changes a row.
 */
public interface ConsentRecords {
    /** Appends the record; it must run inside the transaction that offers the login handoff. */
    void append(NewRecord record);
    /** The record appended by the given login attempt. */
    Optional<Recorded> recordedBy(UUID loginAttemptId);
    /** The principal's most recently recorded consent, revoked or not. */
    Optional<Recorded> latest(ConnectionIdentity principal);

    /** How the consent was given; the dashboard's sign-in checkbox is the only source today. */
    enum Source { LOGIN_CHECKBOX }

    record NewRecord(UUID id, ConnectionIdentity principal, String githubUserId, LocalDate documentsVersion,
                     Instant acceptedAt, Instant recordedAt, Source source, UUID loginAttemptId, String correlationId) {
        public NewRecord {
            Objects.requireNonNull(id); Objects.requireNonNull(principal); Objects.requireNonNull(githubUserId);
            Objects.requireNonNull(documentsVersion); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(recordedAt);
            Objects.requireNonNull(source); Objects.requireNonNull(loginAttemptId); Objects.requireNonNull(correlationId);
        }
        @Override public String toString() { return "ConsentRecord[private]"; }
    }

    record Recorded(LocalDate documentsVersion, Instant acceptedAt, Source source, boolean revoked) {
        public Recorded {
            Objects.requireNonNull(documentsVersion); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(source);
        }
    }
}
