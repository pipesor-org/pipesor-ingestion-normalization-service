package org.pipesor.ingestion.github.infrastructure.persistence.jpa;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.postgresql.util.PSQLException;
import org.pipesor.ingestion.configuration.TestConnectionConfiguration;
import org.pipesor.ingestion.github.application.commands.ConnectionCommands.*;
import org.pipesor.ingestion.github.application.queries.ConnectionStatusQuery;
import org.pipesor.ingestion.github.application.services.GitHubConnectionQueries;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.Result;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** Real isolated PostgreSQL/Flyway/JPA transactions; no live identity, GitHub or custody integration. */
@ExtendWith(OutputCaptureExtension.class)
class GitHubMetadataPersistenceTests {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static GitHubConnections commands;
    private static GitHubConnectionQueries queries;
    private static MutableClock clock;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ConnectionJpaEntities.class)
    @Import(TestConnectionConfiguration.class)
    static class MetadataTestConfiguration {
        @Bean Clock clock() { return new MutableClock(); }
    }

    @BeforeAll static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:17.11");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("ingestion").schemas("ingestion").load().migrate();
        context = new SpringApplicationBuilder(MetadataTestConfiguration.class).web(WebApplicationType.NONE)
                .initializers(c -> org.springframework.boot.test.util.TestPropertyValues.of("spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false",
                        "logging.level.org.hibernate.orm.jdbc.error=OFF", "logging.level.org.hibernate.SQL=OFF").applyTo(c))
                .run();
        commands = context.getBean(GitHubConnections.class);
        queries = context.getBean(GitHubConnectionQueries.class);
        clock = (MutableClock) context.getBean(Clock.class);
    }
    @AfterAll static void stopPostgres() { if (context != null) { context.close(); } if (postgres != null) { postgres.stop(); } }
    @BeforeEach void resetClock() { clock.now = NOW; }

    private record Fixture(UUID connection, ConnectionIdentity identity, UUID generation, UUID attempt,
                           StateDigest digest, SecretReference credentialObject, SecretReference pkceObject) {}
    private Fixture prepare() {
        var fixture = fixture(UUID.randomUUID(), new ConnectionIdentity(UUID.randomUUID(), UUID.randomUUID()));
        commands.create(new Create(fixture.connection(), fixture.identity()));
        prepare(fixture, 0, null);
        return fixture;
    }
    private void prepare(Fixture f, long revision, UUID active) {
        commands.prepare(new Prepare(f.connection(), f.attempt(), f.generation(), revision, active, f.digest(),
                NOW.plusSeconds(300), f.credentialObject(), f.pkceObject(), "approved-return", "correlation-" + UUID.randomUUID()));
    }
    private void ready(Fixture f) { commands.ready(new Ready(f.connection(), f.attempt(), new SavedVersion(f.pkceObject(), new SecretReference("pkce-version-" + f.attempt())))); }
    private void claim(Fixture f) { assertEquals(Outcome.CLAIMED, commands.claim(new Claim(f.connection(), f.attempt(), f.identity(), f.digest()))); }
    private Outcome activate(Fixture f) { return commands.activate(new Activate(f.connection(), f.attempt(), "github-shared-user", new SavedVersion(f.credentialObject(), new SecretReference("pair-version-" + f.generation())), NOW.plusSeconds(900), NOW.plusSeconds(1800))); }
    private Fixture active() { var f = prepare(); ready(f); claim(f); assertEquals(Outcome.ACTIVATED, activate(f)); return f; }
    private Fixture next(Fixture f) {
        return fixture(f.connection(), f.identity());
    }
    private Fixture fixture(UUID connection, ConnectionIdentity identity) {
        var generation = UUID.randomUUID(); var attempt = UUID.randomUUID();
        return new Fixture(connection, identity, generation, attempt,
                new StateDigest(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                SecretReference.generationObject(generation), SecretReference.pkceObject(attempt));
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    private Object scalar(String sql, Object... arguments) throws SQLException {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) { statement.setObject(i + 1, arguments[i]); }
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getObject(1); }
        }
    }
    private void sql(String sql, Object... arguments) throws SQLException {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) { statement.setObject(i + 1, arguments[i]); }
            statement.executeUpdate();
        }
    }

    @Test void uuidInsertPrincipalUniquenessAndTechnicalTimestamps() throws Exception {
        var f = prepare();
        var initial = queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow();
        assertEquals(f.connection(), initial.connectionId()); assertEquals(NOW, initial.createdAt()); assertEquals(NOW, initial.updatedAt());
        clock.now = NOW.plusSeconds(1); ready(f); claim(f); assertEquals(Outcome.ACTIVATED, activate(f));
        var updated = queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow();
        assertEquals(NOW, updated.createdAt()); assertEquals(clock.now, updated.updatedAt()); assertEquals(1, updated.revision());
        var failure = assertThrows(ConnectionFailure.class, () -> commands.create(new Create(UUID.randomUUID(), f.identity())));
        assertEquals(ConnectionFailure.Reason.CONFLICT, failure.reason());
        assertNull(failure.getCause());
        assertEquals(1L, scalar("select count(*) from ingestion.github_connections where tenant_id=? and entra_object_id=?", f.identity().tenantId(), f.identity().objectId()));
    }

    @Test void preparationIsDurableBeforeCustodyReadyAndCallbackIsSingleUse() throws Exception {
        var f = prepare();
        assertEquals("PREPARING", scalar("select phase from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals(f.pkceObject().value(), scalar("select pkce_secret_object_ref from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertNull(scalar("select pkce_secret_version_ref from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> claim(f)).reason());
        ready(f); claim(f);
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> claim(f)).reason());
        assertEquals(Outcome.ACTIVATED, activate(f));
        assertEquals("PENDING", scalar("select pkce_cleanup_state from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals(f.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
    }

    @Test void callbackIdentityDigestExpiryAndChangedRevisionAreGuarded() throws Exception {
        var f = prepare(); ready(f);
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class, () -> commands.claim(new Claim(f.connection(), f.attempt(), new ConnectionIdentity(UUID.randomUUID(), f.identity().objectId()), f.digest()))).reason());
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class, () -> commands.claim(new Claim(f.connection(), f.attempt(), f.identity(), new StateDigest(new byte[]{4})))).reason());
        clock.now = NOW.plusSeconds(300);
        assertEquals(Outcome.EXPIRED, commands.claim(new Claim(f.connection(), f.attempt(), f.identity(), f.digest())));
        assertEquals("EXPIRED", scalar("select phase from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals("PENDING", scalar("select pkce_cleanup_state from ingestion.github_authorization_attempts where id=?", f.attempt()));
        clock.now = NOW; var changed = prepare(); ready(changed); commands.disconnect(new Disconnect(changed.connection(), 0));
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> commands.claim(new Claim(changed.connection(), changed.attempt(), changed.identity(), changed.digest()))).reason());
        assertNull(queries.status(new ConnectionStatusQuery(changed.identity())).orElseThrow().activeGenerationId());
    }

    @Test void expiryAfterClaimAndUnlinkBeforeActivationCannotReviveConnection() throws Exception {
        var expired = prepare(); ready(expired); claim(expired); clock.now = NOW.plusSeconds(300);
        assertEquals(Outcome.EXPIRED, activate(expired));
        assertEquals("INVALIDATED", scalar("select state from ingestion.github_connection_generations where id=?", expired.generation()));
        assertEquals("PENDING", scalar("select pkce_cleanup_state from ingestion.github_authorization_attempts where id=?", expired.attempt()));
        clock.now = NOW; var unlinked = prepare(); ready(unlinked); claim(unlinked);
        commands.disconnect(new Disconnect(unlinked.connection(), 0)); assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> activate(unlinked)).reason());
        assertEquals("FAILED", scalar("select phase from ingestion.github_authorization_attempts where id=?", unlinked.attempt()));
        assertEquals("PENDING", scalar("select pkce_cleanup_state from ingestion.github_authorization_attempts where id=?", unlinked.attempt()));
        assertNull(queries.status(new ConnectionStatusQuery(unlinked.identity())).orElseThrow().activeGenerationId());
    }

    @Test void concurrentAttemptsWithEqualRevisionHaveExactlyOneActivationWinner() throws Exception {
        var first = prepare(); var second = next(first); prepare(second, 0, null);
        ready(first); ready(second); claim(first); claim(second);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.<Callable<Outcome>>of(() -> { barrier.await(); return activate(first); }, () -> { barrier.await(); return activate(second); }));
            var values = List.of(results.get(0).get(), results.get(1).get());
            assertEquals(1, values.stream().filter(value -> value == Outcome.ACTIVATED).count());
            assertEquals(1, values.stream().filter(value -> value == Outcome.CONFLICT).count());
        }
        assertEquals(1L, scalar("select count(*) from ingestion.github_connection_generations where connection_id=? and state='ACTIVE'", first.connection()));
        assertEquals(1L, scalar("select count(*) from ingestion.github_authorization_attempts where connection_id=? and phase='FAILED' and pkce_cleanup_state='PENDING'", first.connection()));
    }

    @Test void refreshExclusiveClaimMarkSavedVersionAndActivationAreDurable() throws Exception {
        var f = active(); var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(60)));
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class, () -> commands.claimRefresh(new ClaimRefresh(f.connection(), UUID.randomUUID(), f.generation(), 1, 1, UUID.randomUUID(), NOW.plusSeconds(60)))).reason());
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, owner, 1, new SavedVersion(f.credentialObject(), new SecretReference("must-not-save"))))).reason());
        commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1));
        assertEquals("REQUEST_MAY_BE_IN_FLIGHT", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        var candidate = new SecretReference("candidate-" + UUID.randomUUID());
        commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, owner, 1, new SavedVersion(f.credentialObject(), candidate)));
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class, () -> commands.activateRefresh(new ActivateRefresh(f.connection(), operation, UUID.randomUUID(), 1, NOW.plusSeconds(100), NOW.plusSeconds(200)))).reason());
        assertEquals(Outcome.ACTIVATED, commands.activateRefresh(new ActivateRefresh(f.connection(), operation, owner, 1, NOW.plusSeconds(100), NOW.plusSeconds(200))));
        assertEquals(candidate.value(), scalar("select active_secret_version_ref from ingestion.github_connection_generations where id=?", f.generation()));
        assertEquals("pair-version-" + f.generation(), scalar("select cleanup_target_version_ref from ingestion.github_credential_operations where generation_id=? and kind='CLEANUP' and cleanup_scope='EXACT_VERSION'", f.generation()));
        assertEquals(2L, queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().revision());
    }

    @Test void expiredLeaseNeverAllowsRemoteRetryAndBlockedRefreshStillExcludesAnotherClaim() throws Exception {
        var f = active(); var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(2)));
        commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1)); clock.now = NOW.plusSeconds(3);
        assertEquals(ConnectionFailure.Reason.EXPIRED, assertThrows(ConnectionFailure.class, () -> commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1))).reason());
        commands.blockRefresh(new RefreshOwner(f.connection(), operation, owner, 1));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        assertNull(queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class, () -> commands.claimRefresh(new ClaimRefresh(f.connection(), UUID.randomUUID(), f.generation(), 2, 2, UUID.randomUUID(), NOW.plusSeconds(60)))).reason());
        commands.disconnect(new Disconnect(f.connection(), 2));
        assertEquals("INVALIDATED", scalar("select state from ingestion.github_connection_generations where id=?", f.generation()));
    }

    @Test void disconnectInvalidationAndCleanupIntentRollBackTogetherOnPersistenceFailure() throws Exception {
        var f = active();
        sql("create function ingestion.test_reject_cleanup() returns trigger language plpgsql as $$ begin raise exception 'synthetic write failure'; end; $$");
        sql("create trigger test_reject_cleanup before insert on ingestion.github_credential_operations for each row execute function ingestion.test_reject_cleanup()");
        try {
            var failure = assertThrows(ConnectionFailure.class, () -> commands.disconnect(new Disconnect(f.connection(), 1)));
            assertEquals(ConnectionFailure.Reason.UNAVAILABLE, failure.reason()); assertNull(failure.getCause());
            assertEquals(f.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
            assertEquals("ACTIVE", scalar("select state from ingestion.github_connection_generations where id=?", f.generation()));
        } finally { sql("drop trigger test_reject_cleanup on ingestion.github_credential_operations"); sql("drop function ingestion.test_reject_cleanup()"); }
        commands.disconnect(new Disconnect(f.connection(), 1));
        assertNull(queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        assertEquals(1L, scalar("select count(*) from ingestion.github_credential_operations where generation_id=? and cleanup_scope='INVALIDATED_GENERATION'", f.generation()));
    }

    @Test void staleRefreshCannotReviveAndOldCleanupNeverChangesNewGenerationOrOtherConnection() throws Exception {
        var f = active(); var other = active(); var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(60)));
        commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1));
        commands.disconnect(new Disconnect(f.connection(), 1));
        commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, owner, 1, new SavedVersion(f.credentialObject(), new SecretReference("late-candidate-" + UUID.randomUUID()))));
        assertEquals(Outcome.CONFLICT, commands.activateRefresh(new ActivateRefresh(f.connection(), operation, owner, 1, NOW.plusSeconds(600), NOW.plusSeconds(1200))));
        var fresh = next(f); prepare(fresh, 2, null); ready(fresh); claim(fresh); assertEquals(Outcome.ACTIVATED, activate(fresh));
        var cleanup = (UUID) scalar("select id from ingestion.github_credential_operations where generation_id=? and cleanup_scope='INVALIDATED_GENERATION'", f.generation());
        var cleaner = UUID.randomUUID(); commands.claimCleanup(new ClaimCleanup(f.connection(), cleanup, cleaner, NOW.plusSeconds(100)));
        commands.finishCleanup(new FinishCleanup(f.connection(), cleanup, cleaner, 1, Result.CONFIRMED, Result.CONFIRMED, true));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", cleanup));
        assertEquals(fresh.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        assertEquals(other.generation(), queries.status(new ConnectionStatusQuery(other.identity())).orElseThrow().activeGenerationId());
        assertEquals(2L, scalar("select count(*) from ingestion.github_connection_generations where id in (?,?) and github_user_id='github-shared-user' and state='ACTIVE'", fresh.generation(), other.generation()));
    }

    @Test void oldGenerationCleanupCompletesAfterRelinkAndPkceCleanupTargetsStaySeparate() throws Exception {
        var f = active(); var fresh = next(f); prepare(fresh, 1, f.generation()); ready(fresh); claim(fresh); assertEquals(Outcome.ACTIVATED, activate(fresh));
        var cleanup = (UUID) scalar("select id from ingestion.github_credential_operations where generation_id=? and cleanup_scope='INVALIDATED_GENERATION'", f.generation());
        var owner = UUID.randomUUID(); commands.claimCleanup(new ClaimCleanup(f.connection(), cleanup, owner, NOW.plusSeconds(30)));
        commands.finishCleanup(new FinishCleanup(f.connection(), cleanup, owner, 1, Result.NOT_APPLICABLE, Result.CONFIRMED, true));
        assertEquals("COMPLETED", scalar("select phase from ingestion.github_credential_operations where id=?", cleanup));
        commands.finishPkceCleanup(new FinishPkceCleanup(f.connection(), f.attempt(), Result.CONFIRMED, false));
        assertEquals("BLOCKED", scalar("select pkce_cleanup_state from ingestion.github_authorization_attempts where id=?", f.attempt()));
        commands.finishPkceCleanup(new FinishPkceCleanup(f.connection(), f.attempt(), Result.CONFIRMED, true));
        assertEquals("COMPLETED", scalar("select pkce_cleanup_state from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals(fresh.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        assertEquals("pair-version-" + fresh.generation(), scalar("select active_secret_version_ref from ingestion.github_connection_generations where id=?", fresh.generation()));
    }


    private void assertConstraint(String state, String constraint, org.junit.jupiter.api.function.Executable action) {
        var exception = assertThrows(SQLException.class, action);
        assertEquals(state, exception.getSQLState());
        assertEquals(constraint, assertInstanceOf(PSQLException.class, exception).getServerErrorMessage().getConstraint());
    }
    private UUID unassignedGeneration(UUID connection) throws SQLException {
        var id = UUID.randomUUID();
        sql("insert into ingestion.github_connection_generations(id,connection_id,state,secret_object_ref,revision,created_at,updated_at) values (?,?,'PREPARING',?,0,?,?)",
                id, connection, SecretReference.generationObject(id).value(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        return id;
    }
    private void insertAttempt(UUID connection, UUID tenant, UUID object, UUID generation) throws SQLException {
        var attempt = UUID.randomUUID();
        sql("insert into ingestion.github_authorization_attempts(id,connection_id,tenant_id,entra_object_id,expected_connection_revision,target_generation_id,state_digest,expires_at,phase,pkce_secret_object_ref,pkce_cleanup_state,approved_return_ref,correlation_id,created_at,updated_at) "
                        + "values (?,?,?,?,0,?,?,?,'PREPARING',?,'HELD','approved','synthetic',?,?)",
                attempt, connection, tenant, object, generation, UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.sql.Timestamp.from(NOW.plusSeconds(300)), SecretReference.pkceObject(attempt).value(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }
    private void insertOperation(UUID connection, UUID generation, String kind, String phase, String candidate,
                                 String scope, String target, String upstream, String vault, Instant completed) throws SQLException {
        boolean pending = phase.equals("PENDING");
        sql("insert into ingestion.github_credential_operations(id,connection_id,generation_id,expected_connection_revision,expected_generation_revision,kind,phase,owner_id,fence,started_at,deadline_at,candidate_secret_version_ref,cleanup_scope,cleanup_target_version_ref,upstream_result,vault_result,completed_at,created_at,updated_at) "
                        + "values (?,?,?,0,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), connection, generation, kind, phase, pending ? null : UUID.randomUUID(), pending ? 0 : 1,
                pending ? null : java.sql.Timestamp.from(NOW), pending ? null : java.sql.Timestamp.from(NOW.plusSeconds(30)), candidate,
                scope, target, upstream, vault, completed == null ? null : java.sql.Timestamp.from(completed), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    @Test void compositeForeignKeysRejectCrossedTenantConnectionAndGenerationMembership() throws Exception {
        var f = prepare(); var other = prepare();
        var target = unassignedGeneration(f.connection());
        assertConstraint("23503", "github_attempts_identity_fk", () -> insertAttempt(f.connection(), UUID.randomUUID(), f.identity().objectId(), target));
        var foreign = unassignedGeneration(other.connection());
        assertConstraint("23503", "github_attempts_target_generation_fk", () -> insertAttempt(f.connection(), f.identity().tenantId(), f.identity().objectId(), foreign));
        assertConstraint("23503", "github_operations_generation_fk", () -> insertOperation(f.connection(), foreign, "CLEANUP", "PENDING", null, "INVALIDATED_GENERATION", null, "PENDING", "PENDING", null));
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("update ingestion.github_connections set active_generation_id=?,revision=revision+1 where id=?")) {
                statement.setObject(1, foreign); statement.setObject(2, f.connection()); statement.executeUpdate();
            }
            assertConstraint("23503", "github_connections_active_generation_fk", () -> {
                try (var statement = connection.createStatement()) { statement.execute("set constraints ingestion.github_connections_active_generation_fk immediate"); }
            });
            connection.rollback();
        }
    }

    @Test void pointerStateEquivalenceIsDeferredUntilCommitAndCoordinatedTransitionCommits() throws Exception {
        var f = active();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("update ingestion.github_connections set active_generation_id=null,revision=revision+1 where id=?")) {
                statement.setObject(1, f.connection()); assertEquals(1, statement.executeUpdate());
            }
            assertConstraint("23514", "github_active_consistency_ck", connection::commit);
            connection.rollback();
        }
        assertEquals(f.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("update ingestion.github_connections set active_generation_id=null,revision=revision+1 where id=?")) {
                statement.setObject(1, f.connection()); statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("update ingestion.github_connection_generations set state='INVALIDATED',revision=revision+1 where id=?")) {
                statement.setObject(1, f.generation()); statement.executeUpdate();
            }
            connection.commit();
        }
        assertNull(queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
    }

    @Test void databasePartialIndexesProtectOneActiveAndOneNonterminalRefreshIncludingBlocked() throws Exception {
        var f = active();
        var duplicate = UUID.randomUUID();
        assertConstraint("23505", "github_generations_one_active_uq", () -> sql("insert into ingestion.github_connection_generations(id,connection_id,github_user_id,state,secret_object_ref,active_secret_version_ref,access_expires_at,refresh_expires_at,activated_at,revision,created_at,updated_at) "
                        + "select ?,connection_id,github_user_id,state,?,active_secret_version_ref,access_expires_at,refresh_expires_at,activated_at,revision,created_at,updated_at from ingestion.github_connection_generations where id=?",
                duplicate, SecretReference.generationObject(duplicate).value(), f.generation()));
        insertOperation(f.connection(), f.generation(), "REFRESH", "BLOCKED", null, null, null, null, null, null);
        assertConstraint("23505", "github_operations_one_refresh_uq", () -> insertOperation(f.connection(), f.generation(), "REFRESH", "CLAIMED", null, null, null, null, null, null));
    }

    @Test void rowShapesPhaseGraphsAndImmutableTargetsFailWithSpecificConstraints() throws Exception {
        var f = prepare();
        assertConstraint("23514", "github_connection_generations_version_ref_ck", () -> sql("update ingestion.github_connection_generations set active_secret_version_ref='latest',revision=revision+1 where id=?", f.generation()));
        assertConstraint("23514", "github_authorization_attempts_version_ref_ck", () -> sql("update ingestion.github_authorization_attempts set pkce_secret_version_ref=' LATEST ' where id=?", f.attempt()));
        assertConstraint("23514", "github_credential_operations_exact_versions_ck", () -> insertOperation(f.connection(), f.generation(), "REFRESH", "VERSION_SAVED", "latest", null, null, null, null, null));
        assertConstraint("23514", "github_credential_operations_exact_versions_ck", () -> insertOperation(f.connection(), f.generation(), "CLEANUP", "PENDING", null, "EXACT_VERSION", "LATEST", "PENDING", "PENDING", null));
        assertConstraint("23514", "github_authorization_attempts_verifier_required_ck", () -> sql("update ingestion.github_authorization_attempts set phase='READY' where id=?", f.attempt()));
        ready(f);
        assertConstraint("23514", "github_authorization_attempts_guard_ck", () -> sql("update ingestion.github_authorization_attempts set phase='COMPLETED',consumed_at=?,pkce_cleanup_state='PENDING' where id=?", java.sql.Timestamp.from(NOW), f.attempt()));
        assertConstraint("23514", "github_attempts_cleanup_terminal_ck", () -> sql("update ingestion.github_authorization_attempts set pkce_cleanup_state='COMPLETED',pkce_cleanup_result='CONFIRMED' where id=?", f.attempt()));
        assertConstraint("23514", "github_connection_generations_guard_ck", () -> sql("update ingestion.github_connection_generations set secret_object_ref='different',revision=revision+1 where id=?", f.generation()));
        assertConstraint("23514", "github_connections_guard_ck", () -> sql("update ingestion.github_connections set created_at=created_at-interval '1 day',revision=revision+1 where id=?", f.connection()));
        assertConstraint("23514", "github_credential_operations_candidate_version_ck", () -> insertOperation(f.connection(), f.generation(), "REFRESH", "VERSION_SAVED", null, null, null, null, null, null));
        assertConstraint("23514", "github_credential_operations_phase_ck", () -> insertOperation(f.connection(), f.generation(), "CLEANUP", "REQUEST_MAY_BE_IN_FLIGHT", null, "INVALIDATED_GENERATION", null, "PENDING", "PENDING", null));
        assertConstraint("23514", "github_credential_operations_cleanup_target_ck", () -> insertOperation(f.connection(), f.generation(), "CLEANUP", "PENDING", null, "EXACT_VERSION", null, "PENDING", "PENDING", null));
        assertConstraint("23514", "github_credential_operations_cleanup_complete_ck", () -> insertOperation(f.connection(), f.generation(), "CLEANUP", "COMPLETED", null, "INVALIDATED_GENERATION", null, "CONFIRMED", "UNAVAILABLE", NOW));
        assertConstraint("23514", "github_authorization_attempts_no_truncate_ck", () -> sql("truncate ingestion.github_authorization_attempts"));
    }

    @Test void expiredRemoteResponsePreservesExactCandidateAndBlocksOldPair() throws Exception {
        var f = active(); var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(2)));
        commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1));
        clock.now = NOW.plusSeconds(3); var candidate = new SecretReference("late-version-" + UUID.randomUUID());
        commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, owner, 1, new SavedVersion(f.credentialObject(), candidate)));
        assertEquals(candidate.value(), scalar("select candidate_secret_version_ref from ingestion.github_credential_operations where id=?", operation));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        assertEquals("BLOCKED", scalar("select state from ingestion.github_connection_generations where id=?", f.generation()));
        assertNull(queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
    }

    @Test void failedPersistenceAndPrivateMetadataNeverExposeCustodyReferences(CapturedOutput output) throws Exception {
        var f = prepare(); var pending = next(f); var privateMarker = pending.credentialObject().value();
        sql("create function ingestion.test_private_failure() returns trigger language plpgsql as $$ begin raise exception 'private-reference failure %', NEW.secret_object_ref; end; $$");
        sql("create trigger test_private_failure before insert on ingestion.github_connection_generations for each row execute function ingestion.test_private_failure()");
        try {
            var failure = assertThrows(ConnectionFailure.class, () -> commands.prepare(new Prepare(f.connection(), pending.attempt(), pending.generation(), 0, null, pending.digest(), NOW.plusSeconds(300), new SecretReference(privateMarker), pending.pkceObject(), "approved-return", "synthetic")));
            assertEquals(ConnectionFailure.Reason.UNAVAILABLE, failure.reason()); assertNull(failure.getCause());
            assertFalse(failure.toString().contains(privateMarker)); assertFalse(output.getAll().contains(privateMarker));
            assertEquals("[private reference]", new SecretReference(privateMarker).toString());
            assertFalse(new SavedVersion(new SecretReference(privateMarker), new SecretReference(privateMarker)).toString().contains(privateMarker));
            assertEquals(0L, scalar("select count(*) from ingestion.github_connection_generations where id=?", pending.generation()));
            assertEquals(0L, scalar("select count(*) from ingestion.github_authorization_attempts where id=?", pending.attempt()));
        } finally { sql("drop trigger test_private_failure on ingestion.github_connection_generations"); sql("drop function ingestion.test_private_failure()"); }
    }


    @Test void cleanupRetryUsesNewFenceAndCanRecordLateFixedResultsWithoutChangingActiveRoot() throws Exception {
        var f = active(); commands.disconnect(new Disconnect(f.connection(), 1));
        var operation = (UUID) scalar("select id from ingestion.github_credential_operations where generation_id=? and kind='CLEANUP'", f.generation());
        var owner = UUID.randomUUID(); commands.claimCleanup(new ClaimCleanup(f.connection(), operation, owner, NOW.plusSeconds(2)));
        clock.now = NOW.plusSeconds(3);
        commands.finishCleanup(new FinishCleanup(f.connection(), operation, owner, 1, Result.CONFIRMED, Result.UNAVAILABLE, true));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        var nextOwner = UUID.randomUUID(); commands.claimCleanup(new ClaimCleanup(f.connection(), operation, nextOwner, NOW.plusSeconds(60)));
        assertEquals(2L, scalar("select fence from ingestion.github_credential_operations where id=?", operation));
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class, () -> commands.finishCleanup(new FinishCleanup(f.connection(), operation, owner, 1, Result.CONFIRMED, Result.CONFIRMED, true))).reason());
        commands.finishCleanup(new FinishCleanup(f.connection(), operation, nextOwner, 2, Result.CONFIRMED, Result.CONFIRMED, true));
        assertEquals("COMPLETED", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        assertNull(queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
    }

    @Test void allMutableMetadataPreservesCreationAndAdvancesUpdateTimeOnlyForWrites() throws Exception {
        var f = prepare(); clock.now = NOW.plusSeconds(1); ready(f);
        assertEquals(java.sql.Timestamp.from(NOW), scalar("select created_at from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals(java.sql.Timestamp.from(clock.now), scalar("select updated_at from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals(NOW, queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().updatedAt());
        clock.now = NOW.plusSeconds(2); claim(f); assertEquals(Outcome.ACTIVATED, activate(f));
        assertEquals(java.sql.Timestamp.from(NOW), scalar("select created_at from ingestion.github_connection_generations where id=?", f.generation()));
        assertEquals(java.sql.Timestamp.from(clock.now), scalar("select updated_at from ingestion.github_connection_generations where id=?", f.generation()));
        var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(60)));
        clock.now = NOW.plusSeconds(3); commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1));
        assertEquals(java.sql.Timestamp.from(NOW.plusSeconds(2)), scalar("select created_at from ingestion.github_credential_operations where id=?", operation));
        assertEquals(java.sql.Timestamp.from(clock.now), scalar("select updated_at from ingestion.github_credential_operations where id=?", operation));
        assertEquals(java.sql.Timestamp.from(NOW.plusSeconds(2)), scalar("select updated_at from ingestion.github_connection_generations where id=?", f.generation()));
    }

    @Test void pkceAndCredentialConfirmationsMustBelongToIntendedObjects() throws Exception {
        var f = prepare();
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> commands.ready(new Ready(f.connection(), f.attempt(), new SavedVersion(f.credentialObject(), new SecretReference("foreign-pkce-version"))))).reason());
        assertEquals("PREPARING", scalar("select phase from ingestion.github_authorization_attempts where id=?", f.attempt()));
        ready(f); claim(f);
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> commands.activate(new Activate(f.connection(), f.attempt(), "github-user", new SavedVersion(f.pkceObject(), new SecretReference("foreign-pair-version")), NOW.plusSeconds(600), NOW.plusSeconds(900)))).reason());
        assertNull(queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        assertEquals("CLAIMED", scalar("select phase from ingestion.github_authorization_attempts where id=?", f.attempt()));
        commands.abandon(new Abandon(f.connection(), f.attempt(), false));
        assertEquals("FAILED", scalar("select phase from ingestion.github_authorization_attempts where id=?", f.attempt()));
        assertEquals("PENDING", scalar("select pkce_cleanup_state from ingestion.github_authorization_attempts where id=?", f.attempt()));
    }


    @Test void concurrentCallbackClaimsAndRefreshClaimsEachHaveOneWinner() throws Exception {
        var f = prepare(); ready(f); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> claim = () -> {
                barrier.await();
                try { return commands.claim(new Claim(f.connection(), f.attempt(), f.identity(), f.digest())).name(); }
                catch (ConnectionFailure failure) { return failure.reason().name(); }
            };
            var values = executor.invokeAll(List.of(claim, claim));
            assertEquals(java.util.Set.of("CLAIMED", "INVALID_STATE"), java.util.Set.of(values.get(0).get(), values.get(1).get()));
        }
        assertEquals(Outcome.ACTIVATED, activate(f)); var refreshBarrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> refresh = () -> {
                refreshBarrier.await();
                try { commands.claimRefresh(new ClaimRefresh(f.connection(), UUID.randomUUID(), f.generation(), 1, 1, UUID.randomUUID(), NOW.plusSeconds(30))); return "CLAIMED"; }
                catch (ConnectionFailure failure) { return failure.reason().name(); }
            };
            var values = executor.invokeAll(List.of(refresh, refresh));
            assertEquals(java.util.Set.of("CLAIMED", "CONFLICT"), java.util.Set.of(values.get(0).get(), values.get(1).get()));
        }
        assertEquals(1L, scalar("select count(*) from ingestion.github_credential_operations where generation_id=? and kind='REFRESH'", f.generation()));
    }

    @Test void intendedObjectsCannotBeReusedAcrossGenerationsConnectionsOrPkce() throws Exception {
        var f = prepare(); var other = prepare();
        for (var connection : List.of(f.connection(), other.connection())) {
            assertConstraint("23514", "github_generations_object_owner_ck", () -> sql("insert into ingestion.github_connection_generations(id,connection_id,state,secret_object_ref,revision,created_at,updated_at) values (?,?,'PREPARING',?,0,?,?)",
                    UUID.randomUUID(), connection, f.credentialObject().value(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW)));
        }
        var target = unassignedGeneration(f.connection());
        assertConstraint("23514", "github_attempts_pkce_object_owner_ck", () -> sql("insert into ingestion.github_authorization_attempts(id,connection_id,tenant_id,entra_object_id,expected_connection_revision,target_generation_id,state_digest,expires_at,phase,pkce_secret_object_ref,pkce_cleanup_state,approved_return_ref,correlation_id,created_at,updated_at) "
                        + "values (?,?,?,?,0,?,?,?,'PREPARING',?,'HELD','approved','synthetic',?,?)",
                UUID.randomUUID(), f.connection(), f.identity().tenantId(), f.identity().objectId(), target, new byte[]{1,2,3},
                java.sql.Timestamp.from(NOW.plusSeconds(300)), f.credentialObject().value(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW)));
        assertConstraint("23514", "github_generations_object_owner_ck", () -> sql("insert into ingestion.github_connection_generations(id,connection_id,state,secret_object_ref,revision,created_at,updated_at) values (?,?,'PREPARING',?,0,?,?)",
                UUID.randomUUID(), other.connection(), f.pkceObject().value(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW)));
        var pending = next(f);
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class,
                () -> commands.prepare(new Prepare(f.connection(), pending.attempt(), pending.generation(), 0, null, pending.digest(), NOW.plusSeconds(300), f.credentialObject(), pending.pkceObject(), "approved", "synthetic"))).reason());
        assertEquals(0L, scalar("select count(*) from ingestion.github_connection_generations where id=?", pending.generation()));
    }


    @Test void disconnectCancelsEveryUnfinishedAttemptWithDurableIndependentCleanup() throws Exception {
        var preparing = prepare(); var ready = next(preparing); var claimed = next(preparing);
        prepare(ready, 0, null); prepare(claimed, 0, null); ready(ready); ready(claimed); claim(claimed);
        commands.disconnect(new Disconnect(preparing.connection(), 0));
        assertEquals(3L, scalar("select count(*) from ingestion.github_authorization_attempts where connection_id=? and phase='FAILED' and pkce_cleanup_state='PENDING'", preparing.connection()));
        assertEquals(3L, scalar("select count(*) from ingestion.github_connection_generations where connection_id=? and state='INVALIDATED'", preparing.connection()));
        assertEquals(3L, scalar("select count(*) from ingestion.github_credential_operations where connection_id=? and kind='CLEANUP' and cleanup_scope='INVALIDATED_GENERATION' and phase='PENDING'", preparing.connection()));
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> activate(claimed)).reason());
        assertNull(queries.status(new ConnectionStatusQuery(preparing.identity())).orElseThrow().activeGenerationId());
    }


    @Test void expiryAfterSavingRefreshCandidateBlocksActivationAndPreservesRecoveryEvidence() throws Exception {
        var f = active(); var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(2)));
        commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1));
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, owner, 1,
                new SavedVersion(f.credentialObject(), new SecretReference("pair-version-" + f.generation()))))).reason());
        var candidate = new SecretReference("candidate-" + UUID.randomUUID());
        commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, owner, 1, new SavedVersion(f.credentialObject(), candidate)));
        clock.now = NOW.plusSeconds(3);
        assertEquals(Outcome.EXPIRED, commands.activateRefresh(new ActivateRefresh(f.connection(), operation, owner, 1, NOW.plusSeconds(300), NOW.plusSeconds(600))));
        assertEquals(candidate.value(), scalar("select candidate_secret_version_ref from ingestion.github_credential_operations where id=?", operation));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        assertNull(queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
    }

    @Test void relinkInvalidatesBlockedGenerationAndKeepsItsCleanupScopedToOrigin() throws Exception {
        var f = active(); var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(30)));
        commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1));
        commands.blockRefresh(new RefreshOwner(f.connection(), operation, owner, 1));
        var fresh = next(f); prepare(fresh, 2, null); ready(fresh); claim(fresh); assertEquals(Outcome.ACTIVATED, activate(fresh));
        assertEquals("INVALIDATED", scalar("select state from ingestion.github_connection_generations where id=?", f.generation()));
        assertEquals(1L, scalar("select count(*) from ingestion.github_credential_operations where generation_id=? and kind='CLEANUP' and cleanup_scope='INVALIDATED_GENERATION'", f.generation()));
        assertEquals(fresh.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
    }


    @Test void lateCustodyAcknowledgmentAfterBlockingAndRelinkPreservesCandidateWithoutReactivation() throws Exception {
        var f = active(); var operation = UUID.randomUUID(); var owner = UUID.randomUUID();
        commands.claimRefresh(new ClaimRefresh(f.connection(), operation, f.generation(), 1, 1, owner, NOW.plusSeconds(2)));
        commands.requestMayBeInFlight(new RefreshOwner(f.connection(), operation, owner, 1));
        commands.blockRefresh(new RefreshOwner(f.connection(), operation, owner, 1));
        commands.disconnect(new Disconnect(f.connection(), 2));
        var fresh = next(f); prepare(fresh, 3, null); ready(fresh); claim(fresh); assertEquals(Outcome.ACTIVATED, activate(fresh));
        clock.now = NOW.plusSeconds(3); var candidate = new SecretReference("late-confirmed-version-" + UUID.randomUUID());
        var acknowledgment = new SaveRefreshVersion(f.connection(), operation, owner, 1, new SavedVersion(f.credentialObject(), candidate));
        commands.saveRefreshVersion(acknowledgment);
        commands.saveRefreshVersion(acknowledgment);
        assertEquals(candidate.value(), scalar("select candidate_secret_version_ref from ingestion.github_credential_operations where id=?", operation));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        assertEquals("INVALIDATED", scalar("select state from ingestion.github_connection_generations where id=?", f.generation()));
        assertEquals(fresh.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        assertEquals(4L, queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().revision());
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class,
                () -> commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, UUID.randomUUID(), 1, new SavedVersion(f.credentialObject(), candidate)))).reason());
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class,
                () -> commands.saveRefreshVersion(new SaveRefreshVersion(f.connection(), operation, owner, 1, new SavedVersion(f.credentialObject(), new SecretReference("different-version"))))).reason());
        assertEquals(ConnectionFailure.Reason.INVALID_STATE, assertThrows(ConnectionFailure.class, () -> commands.activateRefresh(new ActivateRefresh(f.connection(), operation, owner, 1, NOW.plusSeconds(300), NOW.plusSeconds(600)))).reason());
        assertEquals(ConnectionFailure.Reason.CONFLICT, assertThrows(ConnectionFailure.class, () -> commands.claimRefresh(new ClaimRefresh(f.connection(), UUID.randomUUID(), f.generation(), 4, 3, UUID.randomUUID(), NOW.plusSeconds(60)))).reason());
        assertEquals(1L, scalar("select count(*) from ingestion.github_credential_operations where generation_id=? and kind='REFRESH'", f.generation()));
        assertEquals(candidate.value(), scalar("select candidate_secret_version_ref from ingestion.github_credential_operations where id=?", operation));
        assertEquals(fresh.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
        var cleanup = (UUID) scalar("select id from ingestion.github_credential_operations where generation_id=? and kind='CLEANUP' and cleanup_scope='INVALIDATED_GENERATION'", f.generation());
        var cleaner = UUID.randomUUID();
        commands.claimCleanup(new ClaimCleanup(f.connection(), cleanup, cleaner, clock.now.plusSeconds(30)));
        commands.finishCleanup(new FinishCleanup(f.connection(), cleanup, cleaner, 1, Result.CONFIRMED, Result.CONFIRMED, true));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", cleanup));
        assertNull(scalar("select completed_at from ingestion.github_credential_operations where id=?", cleanup));
        assertEquals("BLOCKED", scalar("select phase from ingestion.github_credential_operations where id=?", operation));
        assertEquals(fresh.generation(), queries.status(new ConnectionStatusQuery(f.identity())).orElseThrow().activeGenerationId());
    }

    private static final class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
