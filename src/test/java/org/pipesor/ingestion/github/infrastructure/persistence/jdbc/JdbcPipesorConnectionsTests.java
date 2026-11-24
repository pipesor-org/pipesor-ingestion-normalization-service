package org.pipesor.ingestion.github.infrastructure.persistence.jdbc;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.github.application.ports.PipesorConnections.Principal;
import org.pipesor.ingestion.github.application.ports.PipesorConnections.Request;
import org.pipesor.ingestion.shared.security.PlatformIds;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL/Flyway connection metadata seeded with every link state; one statement per page. */
class JdbcPipesorConnectionsTests {
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static PostgreSQLContainer postgres;
    private static final AtomicInteger statements = new AtomicInteger();
    private static JdbcPipesorConnections connections;

    @BeforeAll static void start() throws SQLException {
        postgres = new PostgreSQLContainer("postgres:17.11");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("ingestion").schemas("ingestion").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            @Override public <T> java.util.List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
                statements.incrementAndGet();
                return super.query(sql, mapper, arguments);
            }
        };
        connections = new JdbcPipesorConnections(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
        link(TENANT, 101, "101", "ACTIVE", NOW.plus(Duration.ofDays(90)));
        link(TENANT, 102, "102", "INVALIDATED", NOW.plus(Duration.ofDays(90)));
        link(TENANT, 103, "103", "ACTIVE", NOW.minusSeconds(1));
        link(OTHER_TENANT, 104, "104", "ACTIVE", NOW.plus(Duration.ofDays(90)));
        link(TENANT, 105, "999", "ACTIVE", NOW.plus(Duration.ofDays(90)));
        link(TENANT, 107, "107", "ACTIVE", NOW);
    }
    @AfterAll static void stop() { if (postgres != null) { postgres.stop(); } }

    @Test void onlyACurrentlyUsableLinkOfTheSameUserInTheSameTenantIsActive() {
        var request = new Request(TENANT, Set.of(principal(101), principal(102), principal(103), principal(104), principal(105),
                principal(106), principal(107), new Principal(PlatformIds.human(101), "102")));
        int before = statements.get();
        var observed = connections.active(request);
        assertEquals(1, statements.get() - before, "one batched statement per page");
        assertEquals(request, observed.request());
        assertEquals(NOW, observed.observedAt());
        // 102 has no active generation, 103 and 107 expired, 104 is in another tenant, 105 is linked to another
        // GitHub user, 106 never linked, and a principal paired with another GitHub user never matches.
        assertEquals(Set.of(principal(101)), observed.value());
        assertEquals(Set.of(principal(104)), connections.active(new Request(OTHER_TENANT, Set.of(principal(101), principal(104)))).value());
    }

    @Test void anEmptyPageNeedsNoStatement() {
        int before = statements.get();
        assertEquals(Set.of(), connections.active(new Request(TENANT, Set.of())).value());
        assertEquals(before, statements.get());
    }

    @Test void aFullPageIsStillOneStatement() {
        var principals = new java.util.HashSet<Principal>();
        for (int id = 101; id < 201; id++) { principals.add(principal(id)); }
        int before = statements.get();
        assertEquals(Set.of(principal(101)), connections.active(new Request(TENANT, principals)).value());
        assertEquals(1, statements.get() - before);
        principals.add(principal(201));
        assertThrows(IllegalArgumentException.class, () -> new Request(TENANT, principals));
    }

    private static Principal principal(long githubUserId) { return new Principal(PlatformIds.human(githubUserId), Long.toString(githubUserId)); }

    /** One connection whose only generation has the given state; only an ACTIVE generation is the connection's active one. */
    private static void link(UUID tenant, long subjectUser, String generationUser, String state, Instant refreshExpiresAt) throws SQLException {
        var connection = UUID.randomUUID();
        var generation = UUID.randomUUID();
        boolean active = state.equals("ACTIVE");
        var created = Timestamp.from(NOW.minus(Duration.ofDays(1)));
        try (var database = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            database.setAutoCommit(false);
            try (var root = database.prepareStatement("""
                    insert into ingestion.github_connections (id, tenant_id, entra_object_id, revision, active_generation_id, created_at, updated_at)
                    values (?, ?, ?, 1, ?, ?, ?)""")) {
                root.setObject(1, connection); root.setObject(2, tenant); root.setObject(3, PlatformIds.human(subjectUser));
                root.setObject(4, active ? generation : null); root.setTimestamp(5, created); root.setTimestamp(6, created);
                root.executeUpdate();
            }
            try (var row = database.prepareStatement("""
                    insert into ingestion.github_connection_generations (id, connection_id, github_user_id, state, secret_object_ref,
                      active_secret_version_ref, access_expires_at, refresh_expires_at, activated_at, revision, created_at, updated_at)
                    values (?, ?, ?, ?, ?, 'version-1', ?, ?, ?, 1, ?, ?)""")) {
                row.setObject(1, generation); row.setObject(2, connection); row.setString(3, generationUser); row.setString(4, state);
                row.setString(5, "generation:" + generation); row.setTimestamp(6, Timestamp.from(NOW.plus(Duration.ofHours(8))));
                row.setTimestamp(7, Timestamp.from(refreshExpiresAt)); row.setTimestamp(8, created);
                row.setTimestamp(9, created); row.setTimestamp(10, created);
                row.executeUpdate();
            }
            database.commit();
        }
    }
}
