package org.pipesor.ingestion.auth.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.auth.application.ports.ConsentRecords;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code ingestion.consent_records}; the table's trigger rejects every change except the manual revocation. */
public final class JdbcConsentRecords implements ConsentRecords {
    private final JdbcTemplate jdbc;

    public JdbcConsentRecords(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void append(NewRecord record) {
        jdbc.update("""
                insert into ingestion.consent_records (id, tenant_id, entra_object_id, github_user_id, documents_version,
                  accepted_at, recorded_at, source, login_attempt_id, correlation_id)
                values (?, ?, ?, ?, ?, ?, greatest(?::timestamptz, ?::timestamptz), ?, ?, ?)""",
                // Start and callback may run on replicas with slightly different clocks; never record before acceptance.
                record.id(), record.principal().tenantId(), record.principal().objectId(), record.githubUserId(),
                record.documentsVersion(), Timestamp.from(record.acceptedAt()), Timestamp.from(record.recordedAt()),
                Timestamp.from(record.acceptedAt()),
                record.source().name(), record.loginAttemptId(), record.correlationId());
    }

    @Override public Optional<Recorded> recordedBy(UUID loginAttemptId) {
        return jdbc.query("""
                select documents_version, accepted_at, source, revoked_at from ingestion.consent_records
                where login_attempt_id = ?""", JdbcConsentRecords::recorded, loginAttemptId).stream().findFirst();
    }

    @Override public Optional<Recorded> latest(ConnectionIdentity principal) {
        return jdbc.query("""
                select documents_version, accepted_at, source, revoked_at from ingestion.consent_records
                where tenant_id = ? and entra_object_id = ?
                order by recorded_at desc, accepted_at desc, id desc limit 1""",
                JdbcConsentRecords::recorded, principal.tenantId(), principal.objectId()).stream().findFirst();
    }

    private static Recorded recorded(ResultSet row, int index) throws SQLException {
        return new Recorded(row.getObject("documents_version", LocalDate.class), row.getTimestamp("accepted_at").toInstant(),
                Source.valueOf(row.getString("source")), row.getTimestamp("revoked_at") != null);
    }

    @Override public String toString() { return "JdbcConsentRecords[configured]"; }
}
