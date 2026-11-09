package org.pipesor.ingestion.github.infrastructure.persistence.jpa;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.configuration.GitHubPersistenceConfiguration;
import org.pipesor.ingestion.github.application.commands.ConnectionCommands.*;
import org.pipesor.ingestion.github.application.commands.RefreshCommand;
import org.pipesor.ingestion.github.application.commandhandlers.RefreshHandler;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;
import org.pipesor.ingestion.github.domain.model.entities.ConnectionRecords.Phase;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL transactions; only the private custody/provider boundaries are simulated. */
class RefreshExecutionTests {
    static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    static PostgreSQLContainer postgres;
    static ConfigurableApplicationContext context;
    static GitHubConnections commands;
    static RefreshSnapshotReader snapshots;
    static MutableClock clock;
    GitHubCredentialCustody custody;
    GitHubUserTokens tokens;
    GitHubUserTokens.Pair oldPair = new GitHubUserTokens.Pair("old-private-access", "old-private-refresh", NOW.plusSeconds(90), NOW.plusSeconds(900));
    GitHubUserTokens.Pair newPair = new GitHubUserTokens.Pair("new-private-access", "new-private-refresh", NOW.plusSeconds(1400), NOW.plusSeconds(9000));
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ConnectionJpaEntities.class)
    @Import(GitHubPersistenceConfiguration.class)
    static class Setup { @Bean Clock clock() { return new MutableClock(); } }
    static final class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17.11"); postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).defaultSchema("ingestion").schemas("ingestion").load().migrate();
        context = new SpringApplicationBuilder(Setup.class).web(WebApplicationType.NONE).properties(
                "spring.datasource.url=" + postgres.getJdbcUrl(), "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword()).run();
        commands = context.getBean(GitHubConnections.class); clock = (MutableClock) context.getBean(Clock.class);
        snapshots = new JpaRefreshSnapshotReader(SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class)), context.getBean(PlatformTransactionManager.class));
    }
    @AfterAll static void stop() { if (context != null) context.close(); if (postgres != null) postgres.stop(); }
    @BeforeEach void setup() {
        clock.now = NOW; custody = mock(GitHubCredentialCustody.class); tokens = mock(GitHubUserTokens.class);
        when(custody.readPair(any())).thenAnswer(inv -> { outside(); return oldPair; });
        when(custody.savePair(any(), any())).thenAnswer(inv -> { outside(); return new SavedVersion(inv.getArgument(0), new SecretReference("new-exact-version")); });
        when(tokens.refresh(any())).thenAnswer(inv -> { outside(); assertEquals(oldPair.refreshToken(), inv.getArgument(0)); return newPair; });
    }
    static void outside() { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); }
    RefreshHandler handler(GitHubConnections connectionCommands) { return new RefreshHandler(connectionCommands, snapshots, custody, tokens, clock); }
    RefreshCommand active() {
        var id = UUID.randomUUID(); var generation = UUID.randomUUID(); var attempt = UUID.randomUUID();
        var identity = new ConnectionIdentity(UUID.randomUUID(), UUID.randomUUID());
        var digest = new StateDigest(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        commands.create(new Create(id, identity));
        commands.prepare(new Prepare(id, attempt, generation, 0, null, digest, NOW.plusSeconds(60), SecretReference.generationObject(generation), SecretReference.pkceObject(attempt), "approved-return", "correlation"));
        commands.ready(new Ready(id, attempt, new SavedVersion(SecretReference.pkceObject(attempt), new SecretReference("pkce-exact"))));
        commands.claim(new Claim(id, attempt, identity, digest));
        commands.activate(new Activate(id, attempt, "12345", new SavedVersion(SecretReference.generationObject(generation), new SecretReference("old-exact-version")), oldPair.accessExpiresAt(), oldPair.refreshExpiresAt()));
        var s = snapshots.read(id, null);
        return new RefreshCommand(id, identity, generation, s.root().revision(), s.generation().revision(), UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(30));
    }
    @Test void nanosecondCredentialExpiriesAndDeadlineRespectPostgresPrecision() {
        oldPair = new GitHubUserTokens.Pair("old-private-access", "old-private-refresh", NOW.plusSeconds(90).plusNanos(123456789), NOW.plusSeconds(900).plusNanos(987654321));
        var c = active(); var suppliedDeadline = c.deadlineAt().plusNanos(123456789);
        var precise = new RefreshCommand(c.connectionId(), c.identity(), c.generationId(), c.connectionRevision(), c.generationRevision(), c.operationId(), c.ownerId(), suppliedDeadline);
        assertTrue(precise.deadlineAt().isBefore(suppliedDeadline));
        assertEquals(suppliedDeadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS), precise.deadlineAt());
        assertEquals(Outcome.ACTIVATED, handler(commands).handle(precise));
    }

    @Test void definitiveProviderRejectionRequiresReconnection() {
        var c = active(); doThrow(new GitHubUserTokens.Failure(GitHubUserTokens.Failure.Reason.REJECTED)).when(tokens).refresh(any());
        var failure = assertThrowsExactly(org.pipesor.ingestion.github.application.services.GitHubAccessFailure.class, () -> handler(commands).handle(c));
        assertEquals(org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.CONNECTION_REQUIRED, failure.reason());
        assertEquals(Phase.BLOCKED, snapshots.read(c.connectionId(), c.operationId()).operation().phase());
    }
    @Test void refreshExpiryElapsedDuringMarkerPreventsSend() {
        var c = active(); var wired = mock(GitHubConnections.class, org.mockito.AdditionalAnswers.delegatesTo(commands));
        doAnswer(inv -> { commands.requestMayBeInFlight(inv.getArgument(0)); clock.now = oldPair.refreshExpiresAt(); return null; }).when(wired).requestMayBeInFlight(any());
        var extended = new RefreshCommand(c.connectionId(), c.identity(), c.generationId(), c.connectionRevision(), c.generationRevision(), c.operationId(), c.ownerId(), NOW.plusSeconds(2000));
        failure(ConnectionFailure.Reason.EXPIRED, () -> handler(wired).handle(extended)); verifyNoInteractions(tokens);
    }
    @Test void rotatesExactVersionInOrderOutsideSqlAndCannotExecuteAgain() {
        var c = active(); assertEquals(Outcome.ACTIVATED, handler(commands).handle(c));
        var s = snapshots.read(c.connectionId(), c.operationId());
        assertEquals(Phase.COMPLETED, s.operation().phase()); assertEquals("new-exact-version", s.generation().activeSecretVersionRef().value());
        assertEquals(newPair.accessExpiresAt(), s.generation().accessExpiresAt());
        verify(custody).readPair(new SavedVersion(SecretReference.generationObject(c.generationId()), new SecretReference("old-exact-version")));
        failure(ConnectionFailure.Reason.CONFLICT, () -> handler(commands).handle(c)); verify(tokens, times(1)).refresh(any());
    }
    @ParameterizedTest @ValueSource(booleans = {true, false}) void crossSubjectFailsBeforeClaimOrSecretRead(boolean crossTenant) {
        var c = active(); var wrong = new RefreshCommand(c.connectionId(), new ConnectionIdentity(crossTenant ? UUID.randomUUID() : c.identity().tenantId(), crossTenant ? c.identity().objectId() : UUID.randomUUID()), c.generationId(), c.connectionRevision(), c.generationRevision(), c.operationId(), c.ownerId(), c.deadlineAt());
        failure(ConnectionFailure.Reason.CONFLICT, () -> handler(commands).handle(wrong)); verifyNoInteractions(tokens, custody);
        assertEquals(Outcome.ACTIVATED, handler(commands).handle(c));
    }
    @ParameterizedTest @ValueSource(strings = {"read", "provider", "save", "metadata", "activate", "block-failure"})
    void failuresBlockWithoutRetryOrCredentialFallback(String phase) {
        var c = active(); var wired = mock(GitHubConnections.class, org.mockito.AdditionalAnswers.delegatesTo(commands));
        if (phase.equals("read")) doThrow(new IllegalStateException("private-token")).when(custody).readPair(any());
        if (phase.equals("provider") || phase.equals("block-failure")) doThrow(new IllegalStateException("private-token")).when(tokens).refresh(any());
        if (phase.equals("save")) doThrow(new IllegalStateException("private-token")).when(custody).savePair(any(), any());
        if (phase.equals("metadata")) doThrow(new ConnectionFailure(ConnectionFailure.Reason.UNAVAILABLE)).when(wired).saveRefreshVersion(any());
        if (phase.equals("activate")) doThrow(new ConnectionFailure(ConnectionFailure.Reason.UNAVAILABLE)).when(wired).activateRefresh(any());
        if (phase.equals("block-failure")) doThrow(new IllegalStateException("private-block-error")).when(wired).blockRefresh(any());
        failure(ConnectionFailure.Reason.UNAVAILABLE, () -> handler(wired).handle(c));
        var s = snapshots.read(c.connectionId(), c.operationId());
        assertEquals(phase.equals("block-failure") ? Phase.REQUEST_MAY_BE_IN_FLIGHT : Phase.BLOCKED, s.operation().phase());
        assertEquals("old-exact-version", s.generation().activeSecretVersionRef().value());
        if (phase.equals("activate")) assertEquals("new-exact-version", s.operation().candidateSecretVersionRef().value());
        verify(tokens, atMostOnce()).refresh(any());
        failure(ConnectionFailure.Reason.CONFLICT, () -> handler(commands).handle(c));
    }
    @Test void concurrentClaimCannotSendAnotherRefresh() throws Exception {
        var c = active(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var sends = new AtomicInteger();
        doAnswer(inv -> { outside(); sends.incrementAndGet(); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return newPair; }).when(tokens).refresh(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> handler(commands).handle(c));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = new RefreshCommand(c.connectionId(), c.identity(), c.generationId(), c.connectionRevision(), c.generationRevision(), UUID.randomUUID(), UUID.randomUUID(), c.deadlineAt());
            try { failure(ConnectionFailure.Reason.CONFLICT, () -> handler(commands).handle(second)); }
            finally { release.countDown(); }
            assertEquals(Outcome.ACTIVATED, first.get(5, TimeUnit.SECONDS)); assertEquals(1, sends.get());
        }
    }
    @Test void lateReturnedPairIsSavedAsEvidenceButNeverActivated() {
        var c = active(); doAnswer(inv -> { clock.now = c.deadlineAt(); return newPair; }).when(tokens).refresh(any());
        assertEquals(Outcome.EXPIRED, handler(commands).handle(c));
        var s = snapshots.read(c.connectionId(), c.operationId());
        assertEquals(Phase.BLOCKED, s.operation().phase()); assertEquals("new-exact-version", s.operation().candidateSecretVersionRef().value());
        assertNull(s.root().activeGenerationId());
    }
    @Test void disconnectDuringProviderIoCannotResurrectTheLink() {
        var c = active(); doAnswer(inv -> { commands.disconnect(new Disconnect(c.connectionId(), c.connectionRevision())); return newPair; }).when(tokens).refresh(any());
        assertEquals(Outcome.CONFLICT, handler(commands).handle(c));
        var s = snapshots.read(c.connectionId(), c.operationId()); assertNull(s.root().activeGenerationId());
        assertEquals("new-exact-version", s.operation().candidateSecretVersionRef().value()); assertEquals(Phase.BLOCKED, s.operation().phase());
    }
    @Test void deadlineElapsedBeforeMarkerNeverSendsAndAmbientTransactionIsRejected() {
        var c = active(); doAnswer(inv -> { clock.now = c.deadlineAt(); return oldPair; }).when(custody).readPair(any());
        failure(ConnectionFailure.Reason.EXPIRED, () -> handler(commands).handle(c)); verifyNoInteractions(tokens);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> failure(ConnectionFailure.Reason.INVALID_STATE, () -> snapshots.read(c.connectionId(), null)));
    }
    static void failure(ConnectionFailure.Reason reason, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(ConnectionFailure.class, action); assertEquals(reason, failure.reason());
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("private"));
    }
}
