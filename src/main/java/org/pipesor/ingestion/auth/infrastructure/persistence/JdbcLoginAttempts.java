package org.pipesor.ingestion.auth.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.auth.application.ports.LoginAttempts;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.pipesor.ingestion.github.domain.model.valueobjects.StateDigest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Each transition is one conditional statement, so concurrent callbacks or exchanges win at most once. */
public final class JdbcLoginAttempts implements LoginAttempts {
    private final JdbcTemplate jdbc;

    public JdbcLoginAttempts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void create(NewAttempt attempt) {
        jdbc.update("""
                insert into ingestion.github_login_attempts (id, state_digest, pkce_secret_object_ref, pkce_secret_version_ref,
                  return_path, phase, expires_at, correlation_id, created_at, updated_at, consent_version)
                values (?, ?, ?, ?, ?, 'READY', ?, ?, ?, ?, ?)""",
                attempt.id(), attempt.state().value(), attempt.verifier().object().value(), attempt.verifier().version().value(),
                attempt.returnPath(), ts(attempt.expiresAt()), attempt.correlationId(), ts(attempt.createdAt()), ts(attempt.createdAt()),
                attempt.consentVersion());
    }

    @Override public Optional<Claimed> claim(StateDigest state, Instant now) {
        return jdbc.query("""
                update ingestion.github_login_attempts set phase = 'CLAIMED', updated_at = ?
                where state_digest = ? and phase = 'READY' and expires_at > ?
                returning id, pkce_secret_object_ref, pkce_secret_version_ref, return_path, expires_at, correlation_id,
                  created_at, consent_version""",
                (row, index) -> new Claimed(row.getObject("id", UUID.class),
                        new SavedVersion(new SecretReference(row.getString("pkce_secret_object_ref")),
                                new SecretReference(row.getString("pkce_secret_version_ref"))),
                        row.getString("return_path"), row.getTimestamp("expires_at").toInstant(), row.getString("correlation_id"),
                        row.getTimestamp("created_at").toInstant(), row.getObject("consent_version", LocalDate.class)),
                ts(now), state.value(), ts(now)).stream().findFirst();
    }

    @Override public void fail(UUID attemptId, Instant now) {
        jdbc.update("update ingestion.github_login_attempts set phase = 'FAILED', updated_at = ? where id = ? and phase = 'CLAIMED'",
                ts(now), attemptId);
    }

    @Override public void offerHandoff(UUID attemptId, Handoff handoff, Instant now) {
        int updated = jdbc.update("""
                update ingestion.github_login_attempts set phase = 'HANDOFF_READY', handoff_digest = ?, handoff_expires_at = ?,
                  connection_id = ?, generation_id = ?, github_user_id = ?, github_login = ?, updated_at = ?
                where id = ? and phase = 'CLAIMED'""",
                handoff.digest().value(), ts(handoff.expiresAt()), handoff.connectionId(), handoff.generationId(),
                handoff.githubUserId(), handoff.login(), ts(now), attemptId);
        if (updated != 1) throw new IllegalStateException("Login attempt is no longer claimed");
    }

    @Override public Optional<Completed> consumeHandoff(StateDigest handoff, Instant now) {
        return jdbc.query("""
                update ingestion.github_login_attempts set phase = 'COMPLETED', updated_at = ?
                where handoff_digest = ? and phase = 'HANDOFF_READY' and handoff_expires_at > ?
                returning id, handoff_digest, handoff_expires_at, connection_id, generation_id, github_user_id, github_login, return_path""",
                (row, index) -> new Completed(row.getObject("id", UUID.class), new Handoff(new StateDigest(row.getBytes("handoff_digest")),
                        row.getTimestamp("handoff_expires_at").toInstant(), row.getObject("connection_id", UUID.class),
                        row.getObject("generation_id", UUID.class), row.getString("github_user_id"),
                        row.getString("github_login"), row.getString("return_path"))),
                ts(now), handoff.value(), ts(now)).stream().findFirst();
    }

    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }

    @Override public String toString() { return "JdbcLoginAttempts[configured]"; }
}
