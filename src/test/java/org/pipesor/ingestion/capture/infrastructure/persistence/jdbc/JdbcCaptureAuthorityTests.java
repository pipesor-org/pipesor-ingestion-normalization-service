package org.pipesor.ingestion.capture.infrastructure.persistence.jdbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.ports.CaptureWorkflowScopes.NotAdmitted;
import org.pipesor.ingestion.capture.application.ports.CaptureWorkflowScopes.PipelineMismatch;
import org.pipesor.ingestion.capture.application.ports.CaptureWorkflowScopes.Status;
import org.pipesor.ingestion.capture.application.ports.CaptureWorkflowScopes.Sync;
import org.pipesor.ingestion.capture.application.ports.CurrentMachineScope.Decision;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.HookContext;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.Source;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-023 authority on real PostgreSQL: admissions, Pipeline-driven scopes, revocation and legacy import. */
class JdbcCaptureAuthorityTests {
    private static final UUID TENANT = UUID.fromString("24c96be0-50f3-4064-b5e4-643088141ae8");
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final String DOMAIN = "pipesor-local-pilot";
    private static PostgreSQLContainer postgres;
    private static JdbcTemplate jdbc;
    private static JdbcCaptureAuthority authority;

    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17.11");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("ingestion").schemas("ingestion").load().migrate();
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        authority = new JdbcCaptureAuthority(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)),
                "5722735", DOMAIN, Duration.ofMinutes(20), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll static void stop() { postgres.stop(); }

    @BeforeEach void clean() {
        jdbc.update("delete from ingestion.capture_workflow_scopes");
        jdbc.update("delete from ingestion.repository_admissions");
        authority.importAdmission(TENANT, "341855203", "181117842", "1446318020", "test", NOW);
    }

    @Test void registrationMakesAWorkflowCapturableAndPausingRevokesIt() {
        var pipeline = UUID.randomUUID();
        assertTrue(authority.completedScope(DOMAIN, TENANT, "1446318020", "500").isEmpty());

        var active = authority.sync(new Sync(TENANT, "1446318020", "500", pipeline, Status.ACTIVE, NOW));
        assertEquals(1, active.revision());
        assertEquals("scope-r1", active.scopeRevision());
        var scope = authority.completedScope(DOMAIN, TENANT, "1446318020", "500").orElseThrow();
        assertEquals("181117842", scope.installationId());
        assertEquals("341855203", scope.accountId());
        assertEquals(Decision.AUTHORIZED, authority.verify(scope));
        assertEquals(NOW.plus(Duration.ofMinutes(20)), authority.completedReplayUntil(scope));

        var repeated = authority.sync(new Sync(TENANT, "1446318020", "500", pipeline, Status.ACTIVE, NOW));
        assertEquals(1, repeated.revision());

        var paused = authority.sync(new Sync(TENANT, "1446318020", "500", pipeline, Status.PAUSED, NOW));
        assertEquals("scope-r2", paused.scopeRevision());
        assertTrue(authority.completedScope(DOMAIN, TENANT, "1446318020", "500").isEmpty());
        assertEquals(Decision.CHANGED_OR_LOST, authority.verify(scope));
        var failure = assertThrows(CaptureFailure.class, () -> authority.completedReplayUntil(scope));
        assertEquals(CaptureFailure.Reason.SCOPE_CHANGED, failure.reason());

        var resumed = authority.sync(new Sync(TENANT, "1446318020", "500", pipeline, Status.ACTIVE, NOW));
        assertEquals("scope-r3", resumed.scopeRevision());
        assertEquals(Decision.CHANGED_OR_LOST, authority.verify(scope), "an old scope revision stays revoked");
    }

    @Test void refusesUnadmittedRepositoriesAndForeignPipelines() {
        assertThrows(NotAdmitted.class,
                () -> authority.sync(new Sync(TENANT, "999", "500", UUID.randomUUID(), Status.ACTIVE, NOW)));
        assertThrows(NotAdmitted.class,
                () -> authority.sync(new Sync(UUID.randomUUID(), "1446318020", "500", UUID.randomUUID(), Status.ACTIVE, NOW)));
        authority.sync(new Sync(TENANT, "1446318020", "500", UUID.randomUUID(), Status.ACTIVE, NOW));
        assertThrows(PipelineMismatch.class,
                () -> authority.sync(new Sync(TENANT, "1446318020", "500", UUID.randomUUID(), Status.ACTIVE, NOW)));
    }

    @Test void importedLegacyScopesKeepTheirRevisionWhenPipelineFirstBindsThem() {
        var legacy = new CaptureScope(DOMAIN, TENANT, "5722735", "341855203", "181117842", "1446318020", "412504984",
                "local-pilot-2026-11-21-r1");
        authority.importScope(legacy, Set.of("completed"), Duration.ofMinutes(20), NOW);
        authority.importScope(legacy, Set.of("completed"), Duration.ofMinutes(20), NOW);
        assertEquals(Decision.AUTHORIZED, authority.verify(legacy));

        var bound = authority.sync(new Sync(TENANT, "1446318020", "412504984", UUID.randomUUID(), Status.ACTIVE, NOW));
        assertEquals("local-pilot-2026-11-21-r1", bound.scopeRevision());
        assertEquals(Decision.AUTHORIZED, authority.verify(legacy), "evidence captured before the import stays verifiable");
    }

    @Test void admissionsResolveRepositoriesAndWebhooksFollowActiveScopes() {
        assertTrue(authority.admitted(TENANT, "341855203", new RepositoryTarget("1446318020", "181117842")));
        assertFalse(authority.admitted(TENANT, "341855203", new RepositoryTarget("1446318020", "1")));
        var hook = new HookContext(DOMAIN, TENANT, "5722735");
        var webhook = authority.webhook(hook);
        var source = new Source("completed", "181117842", "341855203", "1446318020", "500", "77", 1);
        assertNull(webhook.apply(source));
        authority.sync(new Sync(TENANT, "1446318020", "500", UUID.randomUUID(), Status.ACTIVE, NOW));
        assertNotNull(webhook.apply(source));
        assertNull(webhook.apply(new Source("requested", "181117842", "341855203", "1446318020", "500", "77", 1)));
        assertThrows(IllegalArgumentException.class, () -> authority.webhook(new HookContext(DOMAIN, TENANT, "1")));
    }
}
