package org.pipesor.ingestion.capture.infrastructure.persistence.jpa;

import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.util.PSQLException;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.commandhandlers.PrepareGitHubCapture;
import org.pipesor.ingestion.capture.application.commandhandlers.PreparationWorker;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.pipesor.ingestion.capture.application.services.FailedRunEvidenceProfile;
import org.pipesor.ingestion.capture.infrastructure.messaging.NormalizedOutputEncoder;
import org.pipesor.ingestion.normalization.infrastructure.protection.BoundedEvidenceProtection;
import java.nio.charset.StandardCharsets;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.*;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.domain.services.ObservationFingerprint;
import org.pipesor.ingestion.configuration.TestLedgerConfiguration;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.ConnectionJpaEntities;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.commands.NormalizeObservationCommand;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.StageName.TESTING;

/** Real PostgreSQL 17.11/Flyway/JPA; only source authorization, protection and broker are simulated. */
@ExtendWith(OutputCaptureExtension.class)
class CapturePersistenceTests {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Instant SOURCE_TIME = Instant.parse("2026-11-08T11:59:00.999999999Z");
    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static CaptureLedger ledger;
    private static CaptureHandler handler;
    private static MutableClock clock;
    private static ScopeDouble authorization;
    private static BrokerDouble broker;
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = { CaptureJpaEntities.class, ConnectionJpaEntities.class })
    @Import(TestLedgerConfiguration.class)
    static class TestConfiguration {
        @Bean Clock clock() { return new MutableClock(); }
        @Bean ScopeDouble scope() { return new ScopeDouble(); }
        @Bean BrokerDouble broker() { return new BrokerDouble(); }
    }
    static final class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    static final class ScopeDouble implements CurrentMachineScope {
        volatile Decision decision = Decision.AUTHORIZED;
        volatile int calls;
        volatile int changeOnCall = Integer.MAX_VALUE;
        volatile Decision afterCallDecision = Decision.CHANGED_OR_LOST;
        @Override public Decision verify(CaptureScope expected) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "scope I/O must be outside SQL transaction");
            return ++calls >= changeOnCall ? afterCallDecision : decision;
        }
    }
    static final class BrokerDouble implements BrokerPublisher {
        final List<PublicationOutput> sent = new ArrayList<>();
        boolean fail;
        Instant advanceTo;
        @Override public void publish(PublicationOutput output) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "broker I/O must be outside SQL transaction");
            sent.add(output);
            if (advanceTo != null) { clock.now = advanceTo; }
            if (fail) { throw new IllegalStateException("private-provider-token synthetic-secret"); }
        }
    }
    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17.11"); postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("ingestion").schemas("ingestion").load().migrate();
        context = new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE)
                .initializers(c -> org.springframework.boot.test.util.TestPropertyValues.of("spring.datasource.url=" + postgres.getJdbcUrl(), "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(), "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false").applyTo(c))
                .run("--ingestion.capture.enabled=true");
        ledger = context.getBean(CaptureLedger.class); handler = context.getBean(CaptureHandler.class);
        clock = (MutableClock) context.getBean(Clock.class); authorization = context.getBean(ScopeDouble.class); broker = context.getBean(BrokerDouble.class);
    }
    @AfterAll static void stop() { if (context != null) { context.close(); } if (postgres != null) { postgres.stop(); } }
    @BeforeEach void reset() {
        clock.now = NOW; authorization.decision = CurrentMachineScope.Decision.AUTHORIZED; authorization.calls = 0;
        authorization.changeOnCall = Integer.MAX_VALUE; broker.fail = false; broker.advanceTo = null; broker.sent.clear();
        authorization.afterCallDecision = CurrentMachineScope.Decision.CHANGED_OR_LOST;
    }
    private CaptureScope scope() { return new CaptureScope("test-" + UUID.randomUUID(), UUID.randomUUID(), "1", "2", "3", "4", "5", "scope-1"); }
    private String digest(String value) { return ObservationFingerprint.hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private Accept acceptance(CaptureScope scope, String body, String delivery, String correlation) {
        return new Accept(scope, scope, "github_actions", delivery, digest(body), "9007199254740993999999", 2, correlation, NOW.plusSeconds(3600));
    }
    private CaptureReceipt accept() { return handler.accept(acceptance(scope(), "synthetic body", UUID.randomUUID().toString(), "original-correlation")); }
    private CaptureReceipt claim(CaptureReceipt receipt) { return handler.claimPreparation(new Claim(receipt.id(), UUID.randomUUID(), clock.now.plusSeconds(30))); }
    private WorkflowObservation observation(CaptureReceipt receipt, boolean failed) {
        return new WorkflowObservation(receipt.scope().context(), receipt.scope().pipeline(),
                new WorkflowObservation.Execution(receipt.runId(), receipt.runAttempt(), "main", "completed", failed ? "failure" : "success", SOURCE_TIME.minusSeconds(30), SOURCE_TIME),
                "a".repeat(40), "payments", "test", receipt.correlationId(), SOURCE_TIME, SOURCE_TIME,
                List.of(new WorkflowObservation.Stage(TESTING, "completed", SOURCE_TIME.minusSeconds(20), SOURCE_TIME)),
                failed ? new WorkflowObservation.Failure(TESTING, "synthetic failure", SOURCE_TIME) : null,
                failed ? new WorkflowObservation.Evidence(TESTING, "synthetic-job", "synthetic protected excerpt", 0L) : null);
    }
    private NormalizeObservationHandler.Result normalized(CaptureReceipt receipt, boolean failed) {
        return new NormalizeObservationHandler(input -> Optional.of(input)).handle(new NormalizeObservationCommand(observation(receipt, failed),
                receipt.scope().context(), receipt.scope().pipeline(), receipt.snapshotMessageId(), failed ? receipt.evidenceMessageId() : null));
    }
    private Prepare preparation(CaptureReceipt receipt, boolean failed) {
        return new Prepare(receipt.id(), receipt.claimOwner(), receipt.claimFence(), normalized(receipt, failed),
                "normalizer-v1", "policy-v1", failed ? "v1:job:42:step:1:index:0" : null, NOW.plusSeconds(120));
    }
    private CaptureReceipt prepared(boolean failed) { var receipt = claim(accept()); return handler.prepare(preparation(receipt, failed)); }
    private boolean publish(CaptureReceipt receipt) { return handler.publishNext(receipt.id(), UUID.randomUUID(), clock.now.plusSeconds(30), clock.now.plusSeconds(1)); }
    private Object scalar(String sql, Object... values) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, values[i]); }
            try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getObject(1); }
        }
    }
    private void sql(String sql, Object... values) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) { statement.setObject(i + 1, values[i]); }
            statement.executeUpdate();
        }
    }
    private CaptureFailure failure(CaptureFailure.Reason reason, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(CaptureFailure.class, action); assertEquals(reason, failure.reason()); assertNull(failure.getCause()); return failure;
    }

    @Test void acceptanceIsAtomicAndEveryDeliveryAliasDetectsConflictingBody() throws Exception {
        var scope = scope(); var first = handler.accept(acceptance(scope, "first", "delivery-1", "original"));
        var replay = handler.accept(acceptance(scope, "first", "delivery-2", "later"));
        assertEquals(first.id(), replay.id()); assertEquals(first.snapshotMessageId(), replay.snapshotMessageId());
        assertEquals("original", replay.correlationId()); assertNotEquals(first.snapshotMessageId(), first.evidenceMessageId());
        failure(CONFLICT, () -> handler.accept(acceptance(scope, "different", "delivery-2", "later")));
        assertEquals(1L, scalar("select count(*) from ingestion.capture_receipts where capture_domain=?", scope.captureDomain()));
        assertEquals(2L, scalar("select count(*) from ingestion.capture_delivery_aliases where receipt_id=?", first.id()));
    }
    @Test void concurrentSameBodyAcceptanceReturnsOneReceiptAndAllAliases() throws Exception {
        var scope = scope(); var barrier = new CyclicBarrier(4);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(java.util.stream.IntStream.range(0, 4).<Callable<CaptureReceipt>>mapToObj(index -> () -> {
                barrier.await(); return ledger.accept(acceptance(scope, "same", "delivery-" + index, "correlation-" + index), NOW);
            }).toList());
            var id = results.getFirst().get().id(); for (var result : results) { assertEquals(id, result.get().id()); }
            assertEquals(4L, scalar("select count(*) from ingestion.capture_delivery_aliases where receipt_id=?", id));
        }
    }
    @Test void concurrentSameAliasDifferentBodiesRollsBackLosingReceipt() throws Exception {
        var scope = scope(); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.<Callable<Boolean>>of(() -> acceptRace(scope, "one", barrier), () -> acceptRace(scope, "two", barrier)));
            assertEquals(1, results.stream().filter(result -> { try { return result.get(); } catch (Exception error) { throw new AssertionError(error); } }).count());
            assertEquals(1L, scalar("select count(*) from ingestion.capture_receipts where capture_domain=?", scope.captureDomain()));
        }
    }
    private boolean acceptRace(CaptureScope scope, String body, CyclicBarrier barrier) throws Exception {
        barrier.await(); try { ledger.accept(acceptance(scope, body, "same-delivery", "corr"), NOW); return true; }
        catch (CaptureFailure failure) { assertEquals(CONFLICT, failure.reason()); return false; }
    }
    @Test void preparationClaimsAreExclusiveAndExpiredOwnerCannotFreeze() throws Exception {
        var receipt = accept(); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.<Callable<CaptureReceipt>>of(() -> claimRace(receipt, barrier), () -> claimRace(receipt, barrier)));
            var claims = new ArrayList<CaptureReceipt>(); for (var result : results) { if (result.get() != null) { claims.add(result.get()); } }
            assertEquals(1, claims.size()); var first = claims.getFirst(); clock.now = NOW.plusSeconds(30);
            var second = claim(receipt); assertEquals(first.claimFence() + 1, second.claimFence());
            failure(STALE_CLAIM, () -> handler.prepare(preparation(first, true)));
            assertEquals("PREPARED", handler.prepare(preparation(second, true)).phase());
        }
    }
    private CaptureReceipt claimRace(CaptureReceipt receipt, CyclicBarrier barrier) throws Exception {
        barrier.await(); try { return ledger.claimPreparation(new Claim(receipt.id(), UUID.randomUUID(), NOW.plusSeconds(30)), NOW); }
        catch (CaptureFailure failure) { assertEquals(STALE_CLAIM, failure.reason()); return null; }
    }
    @Test void failedPlanFreezesBothOutputsAndNanosecondsBeforeSending() throws Exception {
        var receipt = prepared(true);
        assertTrue(broker.sent.isEmpty()); assertEquals(2L, scalar("select count(*) from ingestion.publication_outputs where observation_id=?", receipt.observationId()));
        assertEquals(999, scalar("select observed_at_submicro from ingestion.capture_observations where id=?", receipt.observationId()));
        assertEquals(999, scalar("select source_updated_at_submicro from ingestion.capture_observations where id=?", receipt.observationId()));
        assertTrue(publish(receipt));
        var output = broker.sent.getFirst(); assertEquals(receipt.snapshotMessageId(), output.id());
        var body = new String(output.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("2026-11-08T11:59:00.999999999Z")); assertTrue(body.contains(receipt.runId()));
        assertEquals("original-correlation", output.correlationId());
        var firstByte = output.body()[0]; var copy = output.body(); copy[0] = 0; assertEquals(firstByte, output.body()[0]);
        assertThrows(UnsupportedOperationException.class, () -> output.applicationProperties().put("eventType", "changed"));
        assertEquals("PREPARED", ledger.receipt(receipt.id()).phase()); assertTrue(publish(receipt));
        assertEquals("PUBLISHED", ledger.receipt(receipt.id()).phase()); assertFalse(publish(receipt));
    }
    @Test void failedSecondOutputInsertRollsBackObservationAndFirstOutput() throws Exception {
        var receipt = claim(accept()); var command = preparation(receipt, true);
        sql("alter table ingestion.publication_outputs add constraint capture_test_reject_output check (id <> '" + receipt.evidenceMessageId() + "') not valid");
        try { failure(UNAVAILABLE, () -> ledger.prepare(command, NOW)); }
        finally { sql("alter table ingestion.publication_outputs drop constraint capture_test_reject_output"); }
        assertEquals("ACCEPTED", ledger.receipt(receipt.id()).phase());
        assertEquals(0L, scalar("select count(*) from ingestion.capture_observations where origin_receipt_id=?", receipt.id()));
        assertEquals(0L, scalar("select count(*) from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
        assertEquals("PREPARED", handler.prepare(command).phase());
    }
    @Test void repeatedObservationRecoversOriginalFrozenIdsAndCorrelation() throws Exception {
        var first = prepared(true);
        var second = handler.accept(acceptance(first.scope(), "different authenticated webhook bytes", "new-delivery", "new-correlation"));
        assertNotEquals(first.snapshotMessageId(), second.snapshotMessageId());
        var reused = handler.prepare(preparation(claim(second), true)); assertEquals(first.observationId(), reused.observationId());
        assertEquals(2L, scalar("select count(*) from ingestion.publication_outputs where observation_id=?", first.observationId()));
        assertTrue(publish(reused)); assertEquals(first.snapshotMessageId(), broker.sent.getFirst().id());
        assertEquals(first.correlationId(), broker.sent.getFirst().correlationId());
        assertTrue(publish(first)); assertEquals("PUBLISHED", ledger.receipt(second.id()).phase());
    }
    @Test void equalSourceTimeDifferentProtectedContentIsDistinct() {
        var first = prepared(true); var second = claim(handler.accept(acceptance(first.scope(), "newbody", "delivery-next", "corr-next")));
        var value = observation(second, true);
        var changed = new WorkflowObservation(value.captureContext(), value.pipeline(), value.execution(), value.commitSha(), value.service(), value.environment(),
                value.correlationId(), value.occurredAt(), value.sourceUpdatedAt(), value.stages(), value.failure(),
                new WorkflowObservation.Evidence(TESTING, "synthetic-job", "changed protected excerpt", 0L));
        var result = new NormalizeObservationHandler(input -> Optional.of(input)).handle(new NormalizeObservationCommand(changed, second.scope().context(), second.scope().pipeline(), second.snapshotMessageId(), second.evidenceMessageId()));
        var prepared = handler.prepare(new Prepare(second.id(), second.claimOwner(), second.claimFence(), result, "v1", "policy-v1", "v1:job:42:step:1:index:0", NOW.plusSeconds(120)));
        assertNotEquals(first.observationId(), prepared.observationId());
    }
    @Test void partialBrokerFailureRetriesOnlyUnconfirmedExactBytesIdsAndRouting(CapturedOutput captured) {
        var receipt = prepared(true); assertTrue(publish(receipt)); broker.fail = true;
        var failure = failure(UNAVAILABLE, () -> publish(receipt)); assertFalse(failure.toString().contains("private"));
        var uncertain = broker.sent.get(1); clock.now = NOW.plusSeconds(1); broker.fail = false; assertTrue(publish(receipt));
        var retried = broker.sent.get(2); assertEquals(uncertain.id(), retried.id()); assertArrayEquals(uncertain.body(), retried.body());
        assertEquals(uncertain.applicationProperties(), retried.applicationProperties()); assertEquals(uncertain.correlationId(), retried.correlationId());
        assertEquals("PUBLISHED", ledger.receipt(receipt.id()).phase()); assertEquals(1, broker.sent.stream().filter(output -> output.id().equals(receipt.snapshotMessageId())).count());
        assertFalse(captured.getAll().contains("private-provider-token")); assertFalse(captured.getAll().contains("synthetic-secret"));
    }
    @Test void brokerFailureAfterRetryDeadlineStillSchedulesStableRetry() throws Exception {
        var receipt = prepared(false); broker.advanceTo = NOW.plusSeconds(5); broker.fail = true;
        failure(UNAVAILABLE, () -> publish(receipt));
        assertEquals("PENDING", scalar("select phase from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
        assertEquals(true, scalar("select next_attempt_at >= ? from ingestion.publication_outputs where id=?", clock.now.atOffset(ZoneOffset.UTC), receipt.snapshotMessageId()));
        var uncertain = broker.sent.getFirst(); broker.fail = false; broker.advanceTo = null; assertTrue(publish(receipt));
        var retry = broker.sent.get(1); assertEquals(uncertain.id(), retry.id()); assertArrayEquals(uncertain.body(), retry.body());
        assertEquals(uncertain.applicationProperties(), retry.applicationProperties()); assertEquals("PUBLISHED", ledger.receipt(receipt.id()).phase());
    }
    @Test void observedFailureDistinguishesFailedFromSuccessfulObservations() {
        // A failed observation opens an incident; a successful one is only a verification execution.
        assertTrue(handler.observedFailure(prepared(true)));
        reset();
        assertFalse(handler.observedFailure(prepared(false)));
        assertFalse(handler.observedFailure(claim(accept())), "no observation before preparation");
        failure(CONFLICT, () -> ledger.observedFailure(UUID.randomUUID()));
    }
    @Test void stolenOutputClaimRejectsOldAcknowledgementAndExpiredAcknowledgement() {
        var receipt = prepared(false); var first = ledger.claimOutput(receipt.observationId(), UUID.randomUUID(), NOW.plusSeconds(10), NOW).orElseThrow();
        assertTrue(ledger.claimOutput(receipt.observationId(), UUID.randomUUID(), NOW.plusSeconds(20), NOW).isEmpty());
        failure(STALE_CLAIM, () -> ledger.confirm(first, NOW.plusSeconds(10)));
        var second = ledger.claimOutput(receipt.observationId(), UUID.randomUUID(), NOW.plusSeconds(30), NOW.plusSeconds(10)).orElseThrow();
        assertEquals(first.fence() + 1, second.fence()); assertArrayEquals(first.body(), second.body());
        failure(STALE_CLAIM, () -> ledger.confirm(first, NOW.plusSeconds(11))); ledger.confirm(second, NOW.plusSeconds(11));
        assertEquals("PUBLISHED", ledger.receipt(receipt.id()).phase());
    }
    @Test void concurrentFinalConfirmationsCannotMissReceiptCompletion() throws Exception {
        var receipt = prepared(true);
        var snapshot = ledger.claimOutput(receipt.observationId(), UUID.randomUUID(), NOW.plusSeconds(30), NOW).orElseThrow();
        var evidence = ledger.claimOutput(receipt.observationId(), UUID.randomUUID(), NOW.plusSeconds(30), NOW).orElseThrow();
        assertNotEquals(snapshot.id(), evidence.id());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var confirmations = executor.invokeAll(List.<Callable<Void>>of(
                    () -> { barrier.await(); ledger.confirm(snapshot, NOW); return null; },
                    () -> { barrier.await(); ledger.confirm(evidence, NOW); return null; }));
            for (var confirmation : confirmations) { confirmation.get(); }
        }
        assertEquals("PUBLISHED", ledger.receipt(receipt.id()).phase());
    }
    @Test void concurrentScopeBlockAndConfirmationUseConsistentLockOrder() throws Exception {
        var receipt = prepared(false);
        var output = ledger.claimOutput(receipt.observationId(), UUID.randomUUID(), NOW.plusSeconds(30), NOW).orElseThrow();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var operations = executor.invokeAll(List.<Callable<Void>>of(
                    () -> { barrier.await(); ledger.blockReceipt(receipt.id(), NOW); return null; },
                    () -> { barrier.await(); ledger.confirm(output, NOW); return null; }));
            for (var operation : operations) { operation.get(); }
        }
        assertTrue(Set.of("PUBLISHED", "BLOCKED").contains(ledger.receipt(receipt.id()).phase()));
    }
    @Test void scopeUnavailablePreservesIntentAndLostScopeBlocksWithoutSending() {
        var receipt = prepared(true); authorization.decision = CurrentMachineScope.Decision.UNAVAILABLE;
        failure(UNAVAILABLE, () -> publish(receipt)); assertEquals("PREPARED", ledger.receipt(receipt.id()).phase());
        authorization.decision = CurrentMachineScope.Decision.CHANGED_OR_LOST;
        failure(SCOPE_CHANGED, () -> publish(receipt)); assertEquals("BLOCKED", ledger.receipt(receipt.id()).phase()); assertTrue(broker.sent.isEmpty());
    }
    @Test void scopeCrossingBetweenClaimAndSendBlocksOutputPreservingBytes() throws Exception {
        var receipt = prepared(true); authorization.changeOnCall = authorization.calls + 2;
        failure(SCOPE_CHANGED, () -> publish(receipt)); assertTrue(broker.sent.isEmpty());
        assertEquals("BLOCKED", scalar("select phase from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
        assertNotNull(scalar("select payload from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
    }
    @Test void lateScopeDenialPreservesNewClaimAndOriginalScopeError() throws Exception {
        var receipt = prepared(false); var owner = UUID.randomUUID(); var checks = new java.util.concurrent.atomic.AtomicInteger();
        var delayed = new CaptureHandler(ledger, expected -> {
            if (checks.incrementAndGet() == 2) {
                clock.now = NOW.plusSeconds(30);
                ledger.claimOutput(receipt.observationId(), owner, NOW.plusSeconds(60), clock.now).orElseThrow();
                return CurrentMachineScope.Decision.CHANGED_OR_LOST;
            }
            return CurrentMachineScope.Decision.AUTHORIZED;
        }, broker, clock);
        failure(SCOPE_CHANGED, () -> delayed.publishNext(receipt.id(), UUID.randomUUID(), NOW.plusSeconds(30), NOW.plusSeconds(1)));
        assertTrue(broker.sent.isEmpty());
        assertEquals("CLAIMED", scalar("select phase from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
        assertEquals(owner, scalar("select claim_owner from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
    }
    @Test void scopeOutageAfterClaimReleasesForStableRetryWithoutSending() throws Exception {
        var receipt = prepared(false); authorization.changeOnCall = authorization.calls + 2;
        authorization.afterCallDecision = CurrentMachineScope.Decision.UNAVAILABLE;
        failure(UNAVAILABLE, () -> publish(receipt)); assertTrue(broker.sent.isEmpty());
        assertEquals("PENDING", scalar("select phase from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
        authorization.changeOnCall = Integer.MAX_VALUE; clock.now = NOW.plusSeconds(1); assertTrue(publish(receipt));
        assertEquals(receipt.snapshotMessageId(), broker.sent.getFirst().id()); assertEquals("PUBLISHED", ledger.receipt(receipt.id()).phase());
    }
    @Test void scopeLostBeforePreparationPreventsReadingAndFreezing() {
        var receipt = accept(); authorization.decision = CurrentMachineScope.Decision.CHANGED_OR_LOST;
        failure(SCOPE_CHANGED, () -> claim(receipt)); assertEquals("BLOCKED", ledger.receipt(receipt.id()).phase());
    }
    @Test void missingExplicitHorizonsAndUntrustedScopeAreRejected() {
        var scope = scope();
        failure(INVALID_INPUT, () -> new Accept(scope, scope, "github_actions", "delivery", digest("body"), "1", 1, "corr", null));
        failure(SCOPE_CHANGED, () -> new Accept(scope, scope(), "github_actions", "delivery", digest("body"), "1", 1, "corr", NOW.plusSeconds(1)));
        var receipt = claim(accept());
        failure(INVALID_INPUT, () -> new Prepare(receipt.id(), receipt.claimOwner(), receipt.claimFence(), normalized(receipt, true), "v1", "policy", "unapproved-locator", NOW.plusSeconds(1)));
    }
    @Test void protectionRefusalAndCrossReceiptResultNeverCreatePlan() throws Exception {
        var receipt = claim(accept());
        var protection = new NormalizeObservationHandler(input -> Optional.empty());
        assertEquals(NormalizationFailure.Reason.PROTECTION_REFUSED, assertThrows(NormalizationFailure.class, () -> protection.handle(
                new NormalizeObservationCommand(observation(receipt, true), receipt.scope().context(), receipt.scope().pipeline(), receipt.snapshotMessageId(), receipt.evidenceMessageId()))).reason());
        var other = claim(accept()); var result = normalized(other, true);
        failure(CONFLICT, () -> handler.prepare(new Prepare(receipt.id(), receipt.claimOwner(), receipt.claimFence(), result, "v1", "policy", "v1:job:42:step:1:index:0", NOW.plusSeconds(120))));
        assertEquals(0L, scalar("select count(*) from ingestion.capture_observations where origin_receipt_id=?", receipt.id()));
    }
    @Test void persistedAndPublishedBytesContainOnlyProtectedEvidence(CapturedOutput captured) throws Exception {
        var receipt = claim(accept()); var source = observation(receipt, true); var marker = "raw-secret-" + UUID.randomUUID();
        var raw = new WorkflowObservation(source.captureContext(), source.pipeline(), source.execution(), source.commitSha(), source.service(), source.environment(),
                source.correlationId(), source.occurredAt(), source.sourceUpdatedAt(), source.stages(), source.failure(),
                new WorkflowObservation.Evidence(TESTING, "synthetic-job", marker, 0L));
        var protection = new NormalizeObservationHandler(input -> Optional.of(new EvidenceProtection.Texts(input.branch(), input.service(), input.environment(),
                input.failureSummary(), input.job(), "masked excerpt", input.correlationId(), input.scopeRevision())));
        var result = protection.handle(new NormalizeObservationCommand(raw, receipt.scope().context(), receipt.scope().pipeline(), receipt.snapshotMessageId(), receipt.evidenceMessageId()));
        var prepared = handler.prepare(new Prepare(receipt.id(), receipt.claimOwner(), receipt.claimFence(), result, "v1", "policy", "v1:job:42:step:1:index:0", NOW.plusSeconds(120)));
        var stored = new String((byte[]) scalar("select payload from ingestion.publication_outputs where id=?", receipt.evidenceMessageId()), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(stored.contains(marker)); assertTrue(stored.contains("masked excerpt"));
        assertTrue(publish(prepared)); assertTrue(publish(prepared));
        for (var output : broker.sent) { assertFalse(new String(output.body(), java.nio.charset.StandardCharsets.UTF_8).contains(marker)); }
        assertFalse(captured.getAll().contains(marker));
    }
    @Test void purgeRequiresConfirmationAndExplicitExpiredHorizonAndRetainsDedupMetadata() throws Exception {
        var receipt = prepared(true); assertEquals(0, handler.purgeConfirmedPayloads()); assertTrue(publish(receipt));
        assertEquals(0, handler.purgeConfirmedPayloads()); clock.now = NOW.plusSeconds(120);
        assertTrue(handler.purgeConfirmedPayloads() >= 1);
        assertNull(scalar("select payload from ingestion.publication_outputs where id=?", receipt.snapshotMessageId()));
        assertNotNull(scalar("select payload from ingestion.publication_outputs where id=?", receipt.evidenceMessageId()));
        assertEquals(2L, scalar("select count(*) from ingestion.publication_outputs where observation_id=?", receipt.observationId()));
        var duplicate = handler.accept(acceptance(receipt.scope(), "synthetic body", "later-delivery", "later-correlation")); assertEquals(receipt.id(), duplicate.id());
        assertTrue(publish(receipt)); assertTrue(handler.purgeConfirmedPayloads() >= 1);
        assertEquals("PUBLISHED", ledger.receipt(receipt.id()).phase());
    }
    @Test void sqlFailureIsFixedRedactedAndReceiptAliasRollbackTogether(CapturedOutput captured) throws Exception {
        var scope = scope(); var marker = "private-provider-secret-" + UUID.randomUUID(); var bodyDigest = digest(marker);
        sql("alter table ingestion.capture_receipts add constraint capture_test_reject check (body_digest <> '" + bodyDigest + "') not valid");
        try {
            var error = failure(UNAVAILABLE, () -> handler.accept(acceptance(scope, marker, "private-delivery", "private-correlation")));
            assertEquals("UNAVAILABLE", error.getMessage()); assertFalse(error.toString().contains(marker));
            assertFalse(captured.getAll().contains(bodyDigest)); assertFalse(captured.getAll().contains("private-delivery"));
            assertEquals(0L, scalar("select count(*) from ingestion.capture_receipts where capture_domain=?", scope.captureDomain()));
        } finally { sql("alter table ingestion.capture_receipts drop constraint capture_test_reject"); }
    }
    @Test void sqlCannotRewriteFrozenBytesOrPurgePendingPayload() throws Exception {
        var receipt = prepared(true);
        var now = NOW.atOffset(ZoneOffset.UTC);
        var purge = assertThrows(PSQLException.class, () -> sql("update ingestion.publication_outputs set payload=null,payload_removed_at=?,revision=revision+1,updated_at=? where id=?", now, now, receipt.snapshotMessageId()));
        assertEquals("23514", purge.getSQLState()); assertEquals("publication_outputs_guard_ck", purge.getServerErrorMessage().getConstraint());
        var rewrite = assertThrows(PSQLException.class, () -> sql("update ingestion.publication_outputs set payload=?,revision=revision+1,updated_at=? where id=?", new byte[] { 1 }, now, receipt.snapshotMessageId()));
        assertEquals("23514", rewrite.getSQLState()); assertEquals("publication_outputs_guard_ck", rewrite.getServerErrorMessage().getConstraint());
        var deletion = assertThrows(PSQLException.class, () -> sql("delete from ingestion.capture_delivery_aliases where receipt_id=?", receipt.id()));
        assertEquals("23514", deletion.getSQLState()); assertEquals("capture_delivery_aliases_immutable_ck", deletion.getServerErrorMessage().getConstraint());
    }
    private CaptureReceipt pilotReceipt() {
        var scope = new CaptureScope("pilot-" + UUID.randomUUID(), UUID.randomUUID(), "1", "341855203", "3", "1446318020", "412504984", "scope-1");
        return handler.accept(acceptance(scope, "pilot delivery", UUID.randomUUID().toString(), "pilot-correlation"));
    }
    private GitHubMachineCapture.StepLogs pilotLogs(CaptureReceipt receipt, boolean failed) {
        var request = new GitHubMachineCapture.Request(receipt.scope(), receipt.runId(), receipt.runAttempt(), receipt.correlationId());
        var start = SOURCE_TIME.minusSeconds(20);
        var maven = new GitHubMachineCapture.Step(4, "Compile, test with isolated PostgreSQL and package", "completed", failed ? "failure" : "success", start, start.plusSeconds(10));
        var image = new GitHubMachineCapture.Step(5, "Build local non-root image", "completed", failed ? "skipped" : "success", start.plusSeconds(11), start.plusSeconds(15));
        var job = new GitHubMachineCapture.Job("129926055075", "verify", "completed", failed ? "failure" : "success", start, start.plusSeconds(16), List.of(maven, image));
        var run = new GitHubMachineCapture.Run(request, "pipesor-org/pipesor-ingestion-normalization-service", "feature/pilot", "b".repeat(40), "completed", job.conclusion(), start.minusSeconds(1), SOURCE_TIME, start, List.of(job));
        var text = start.plusSeconds(1) + " [INFO] --- compiler:3.14.1:compile (default-compile) @ ingestion ---\n"
                + start.plusSeconds(2) + " [INFO] --- surefire:3.5.4:test (default-test) @ ingestion ---\n"
                + start.plusSeconds(3) + " [ERROR] password=synthetic-private-password\n"
                + (failed ? start.plusSeconds(4) + " [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project ingestion: failure\n" : start.plusSeconds(4) + " [INFO] Tests complete\n");
        var logs = new ArrayList<GitHubMachineCapture.StepLog>();
        logs.add(new GitHubMachineCapture.StepLog(job.id(), 4, text.getBytes(StandardCharsets.UTF_8)));
        if (!failed) { logs.add(new GitHubMachineCapture.StepLog(job.id(), 5, (start.plusSeconds(12) + " image complete\n").getBytes(StandardCharsets.UTF_8))); }
        return new GitHubMachineCapture.StepLogs(run, logs);
    }
    private PrepareGitHubCapture coordinator(java.util.function.Function<GitHubMachineCapture.Request, GitHubMachineCapture.StepLogs> read,
            EvidenceProtection protection, int maxWireBytes) {
        var github = new GitHubMachineCapture() {
            public Run readRun(Request request) { throw new AssertionError("Use attempt step archive"); }
            public Log readJobLog(Request request, String jobId) { throw new AssertionError("Whole job is not step evidence"); }
            public StepLogs readStepLogs(Request request) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return read.apply(request);
            }
        };
        return new PrepareGitHubCapture(handler, github, new FailedRunEvidenceProfile(1_000_000, 100000), new NormalizeObservationHandler(protection),
                new NormalizedOutputEncoder(), clock, maxWireBytes, "bounded-v1");
    }
    @Test void preparationClaimsBeforeSourceReadAndFreezesOnlyProtectedPlan(CapturedOutput captured) throws Exception {
        var receipt = pilotReceipt();
        var operation = coordinator(request -> {
            var claimed = ledger.receipt(receipt.id());
            assertNotNull(claimed.claimOwner()); assertTrue(claimed.claimFence() > 0);
            assertEquals(request.scope(), claimed.scope());
            return pilotLogs(receipt, true);
        }, new BoundedEvidenceProtection("bounded-v1", 100000, 100000, 100), 120000);
        var prepared = operation.prepare(new Claim(receipt.id(), UUID.randomUUID(), NOW.plusSeconds(30)), NOW.plusSeconds(120));
        assertEquals("PREPARED", prepared.phase());
        assertEquals(2L, scalar("select count(*) from ingestion.publication_outputs where observation_id=?", prepared.observationId()));
        var bytes = (byte[]) scalar("select payload from ingestion.publication_outputs where id=?", receipt.evidenceMessageId());
        var wire = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(wire.contains("synthetic-private-password")); assertTrue(wire.contains("[redacted]"));
        String stepLocator = (String) scalar("select fragment_locator from ingestion.publication_outputs where id=?", receipt.evidenceMessageId());
        assertTrue(stepLocator.matches("v2:job:129926055075:member:verify%2F4_Compile%2C%20test%20with%20isolated%20PostgreSQL%20and%20package\\.txt:lines:1-4:sha256:[0-9a-f]{64}"));
        assertTrue(wire.contains(stepLocator)); assertTrue(wire.contains("\"schema_version\":\"2.2\""));
        assertTrue(publish(prepared)); assertTrue(publish(prepared));
        assertEquals(receipt.snapshotMessageId(), broker.sent.getFirst().id());
        assertEquals(receipt.evidenceMessageId(), broker.sent.getLast().id());
        assertArrayEquals(bytes, broker.sent.getLast().body()); assertFalse(captured.getAll().contains("synthetic-private-password"));
    }
    @Test void aggregateReceiptFreezesProtectedOriginalLineProvenance() throws Exception {
        var receipt = pilotReceipt();
        var source = pilotLogs(receipt, true);
        var raw = new String(source.logs().getFirst().bytes(), StandardCharsets.UTF_8)
                .replace("on project ingestion: failure", "on project ingestion: password=synthetic-private-password");
        var member = new GitHubMachineCapture.AggregateLog("129926055075", "0_verify.txt", raw.getBytes(StandardCharsets.UTF_8));
        var operation = coordinator(request -> new GitHubMachineCapture.StepLogs(source.run(), List.of(), List.of(member)),
                new BoundedEvidenceProtection("bounded-v1", 100000, 100000, 100), 120000);
        var plan = operation.prepare(new Claim(receipt.id(), UUID.randomUUID(), NOW.plusSeconds(30)), NOW.plusSeconds(120));
        String locator = (String) scalar("select fragment_locator from ingestion.publication_outputs where id=?", receipt.evidenceMessageId());
        byte[] body = (byte[]) scalar("select payload from ingestion.publication_outputs where id=?", receipt.evidenceMessageId());
        String wire = new String(body, StandardCharsets.UTF_8);
        assertTrue(locator.matches("v2:job:129926055075:member:0_verify\\.txt:lines:1-4:sha256:[0-9a-f]{64}"));
        assertTrue(wire.contains(locator)); assertTrue(wire.contains("\"schema_version\":\"2.2\""));
        assertTrue(wire.contains("[redacted]")); assertFalse(wire.contains("synthetic-private-password"));
        assertTrue(publish(plan)); assertTrue(publish(plan));
        assertArrayEquals(body, broker.sent.getLast().body());
        if (Boolean.getBoolean("pipesor.test.exportAggregate")) {
            var directory = java.nio.file.Files.createDirectories(java.nio.file.Path.of("target", "synthetic-aggregate"));
            java.nio.file.Files.write(directory.resolve("evidence.json"), body);
            java.nio.file.Files.write(directory.resolve("snapshot.json"), broker.sent.getFirst().body());
        }
    }

    @Test void failedRunWithoutStepOrAggregateEvidenceRemainsUnsupportedWithNoFrozenPlan() throws Exception {
        var receipt = pilotReceipt();
        var operation = coordinator(request -> new GitHubMachineCapture.StepLogs(pilotLogs(receipt, true).run(), List.of()),
                input -> { throw new AssertionError("Unbound evidence reached protection"); }, 120000);
        var worker = new PreparationWorker((now, limit, after) -> List.of(receipt.id()), operation, clock, 1, Duration.ofSeconds(5));
        var result = worker.tick(UUID.randomUUID(), NOW.plusSeconds(30), NOW.plusSeconds(120));
        assertEquals(1, result.unsupported());
        assertEquals(0, result.prepared());
        assertEquals("ACCEPTED", ledger.receipt(receipt.id()).phase());
        assertNull(ledger.receipt(receipt.id()).observationId());
        assertEquals(0L, scalar("select count(*) from ingestion.publication_outputs where id in (?,?)",
                receipt.snapshotMessageId(), receipt.evidenceMessageId()));
    }

    @Test void preparationRejectsCrossReceiptSourceBeforeProtection() throws Exception {
        var receipt = pilotReceipt(); var other = pilotReceipt();
        var operation = coordinator(request -> pilotLogs(other, false), input -> { throw new AssertionError("Crossing reached protection"); }, 120000);
        failure(CONFLICT, () -> operation.prepare(new Claim(receipt.id(), UUID.randomUUID(), NOW.plusSeconds(30)), NOW.plusSeconds(120)));
        assertEquals(0L, scalar("select count(*) from ingestion.capture_observations where origin_receipt_id=?", receipt.id()));
    }
    @Test void expiredPreparationAfterReadCannotProtectOrFreeze() throws Exception {
        var receipt = pilotReceipt();
        var operation = coordinator(request -> { clock.now = NOW.plusSeconds(30); return pilotLogs(receipt, false); },
                input -> { throw new AssertionError("Expired claim reached protection"); }, 120000);
        failure(STALE_CLAIM, () -> operation.prepare(new Claim(receipt.id(), UUID.randomUUID(), NOW.plusSeconds(30)), NOW.plusSeconds(120)));
        assertEquals("ACCEPTED", ledger.receipt(receipt.id()).phase());
    }
    @Test void preparationRejectsOversizedWireAndRevokedScopeWithoutPartialPlan() throws Exception {
        var tooLarge = pilotReceipt();
        var operation = coordinator(request -> pilotLogs(tooLarge, false), input -> Optional.of(input), 10);
        failure(INVALID_INPUT, () -> operation.prepare(new Claim(tooLarge.id(), UUID.randomUUID(), NOW.plusSeconds(30)), NOW.plusSeconds(120)));
        assertEquals(0L, scalar("select count(*) from ingestion.capture_observations where origin_receipt_id=?", tooLarge.id()));
        var revoked = pilotReceipt();
        var revokeOperation = coordinator(request -> {
            authorization.decision = CurrentMachineScope.Decision.CHANGED_OR_LOST; return pilotLogs(revoked, false);
        }, input -> Optional.of(input), 120000);
        failure(SCOPE_CHANGED, () -> revokeOperation.prepare(new Claim(revoked.id(), UUID.randomUUID(), NOW.plusSeconds(30)), NOW.plusSeconds(120)));
        assertEquals("BLOCKED", ledger.receipt(revoked.id()).phase());
        assertEquals(0L, scalar("select count(*) from ingestion.capture_observations where origin_receipt_id=?", revoked.id()));
    }

    private PreparationReadyReceipts preparationReady() {
        return new JpaPreparationReadyReceipts(SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class)),
                context.getBean(PlatformTransactionManager.class));
    }
    @Test void expiredSourceBlocksAClaimedReceiptWithItsReasonAndOnlyKnownReasonsAreStored() {
        var claimed = claim(accept()); var scoped = accept();
        ledger.blockReceipt(claimed.id(), NOW, "SOURCE_EXPIRED");
        ledger.blockReceipt(scoped.id(), NOW);
        var expired = ledger.receipt(claimed.id());
        assertEquals("BLOCKED", expired.phase()); assertEquals("SOURCE_EXPIRED", expired.blockedReason()); assertNull(expired.claimOwner());
        assertEquals("SCOPE_CHANGED", ledger.receipt(scoped.id()).blockedReason());
        assertNull(ledger.receipt(accept().id()).blockedReason());
        assertEquals(INVALID_INPUT, assertThrows(CaptureFailure.class, () -> ledger.blockReceipt(accept().id(), NOW, "ANYTHING")).reason());
    }
    @Test void preparationDiscoveryUsesPhaseAndExactLeaseBoundaryWithoutReplayCutoff() {
        var waiting = accept(); var leased = claim(accept()); var frozen = prepared(false); var blocked = accept();
        ledger.blockReceipt(blocked.id(), NOW);
        var ready = preparationReady();
        var initial = ready.read(NOW, 5000, null);
        assertTrue(initial.contains(waiting.id())); assertFalse(initial.contains(leased.id()));
        assertFalse(initial.contains(frozen.id())); assertFalse(initial.contains(blocked.id()));
        assertEquals(initial.stream().sorted(Comparator.comparing(UUID::toString)).toList(), initial);
        assertEquals(1, ready.read(NOW, 1, null).size());
        var later = ready.read(NOW.plusSeconds(30), 5000, null);
        assertTrue(later.contains(leased.id()));
        assertTrue(ready.read(NOW.plusSeconds(3601), 5000, null).contains(waiting.id()), "Replay window is not a processing deadline");
        assertTrue(ready.read(NOW, 5000, waiting.id()).stream().allMatch(id -> id.toString().compareTo(waiting.id().toString()) > 0));
        failure(INVALID_INPUT, () -> ready.read(NOW, 0, null));
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(status -> failure(INVALID_INPUT, () -> ready.read(NOW, 1, null)));
    }
    @Test void competingPreparationPassesReadSourceOnceAndFreezeOnePlan() throws Exception {
        var receipt = pilotReceipt(); var barrier = new CyclicBarrier(2); var reads = new java.util.concurrent.atomic.AtomicInteger();
        var operation = coordinator(request -> {
            reads.incrementAndGet(); assertNotNull(ledger.receipt(receipt.id()).claimOwner());
            return pilotLogs(receipt, true);
        }, new BoundedEvidenceProtection("bounded-v1", 100000, 100000, 100), 120000);
        PreparationReadyReceipts hints = (now, limit, after) -> {
            try { barrier.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception error) { throw new AssertionError(error); }
            return List.of(receipt.id());
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var passes = executor.invokeAll(List.<Callable<PreparationWorker.Result>>of(
                    () -> new PreparationWorker(hints, operation, clock, 1, Duration.ofSeconds(10)).tick(UUID.randomUUID(), NOW.plusSeconds(30), NOW.plusSeconds(120)),
                    () -> new PreparationWorker(hints, operation, clock, 1, Duration.ofSeconds(10)).tick(UUID.randomUUID(), NOW.plusSeconds(30), NOW.plusSeconds(120))));
            var results = new ArrayList<PreparationWorker.Result>(); for (var pass : passes) { results.add(pass.get()); }
            assertEquals(1, reads.get()); assertEquals(1, results.stream().mapToInt(PreparationWorker.Result::prepared).sum());
            assertEquals(1, results.stream().mapToInt(PreparationWorker.Result::stale).sum());
        }
        var prepared = ledger.receipt(receipt.id()); assertEquals("PREPARED", prepared.phase());
        assertEquals(2L, scalar("select count(*) from ingestion.publication_outputs where observation_id=?", prepared.observationId()));
    }
    @Test void failedSourcePassRetainsClaimAndAllowsLaterReceiptToPrepare(CapturedOutput captured) {
        var receipts = new ArrayList<>(List.of(pilotReceipt(), pilotReceipt()));
        receipts.sort(Comparator.comparing(receipt -> receipt.id().toString()));
        var failed = receipts.getFirst(); var healthy = receipts.getLast(); var ids = Set.of(failed.id(), healthy.id());
        var selector = preparationReady();
        PreparationReadyReceipts isolated = (now, limit, after) -> selector.read(now, 5000, after).stream().filter(ids::contains).limit(limit).toList();
        var reads = new ArrayList<String>();
        var operation = coordinator(request -> {
            reads.add(request.scope().captureDomain());
            if (request.scope().equals(failed.scope())) { throw new IllegalStateException("private dispatch source token"); }
            return pilotLogs(healthy, false);
        }, input -> Optional.of(input), 120000);
        var worker = new PreparationWorker(isolated, operation, clock, 1, Duration.ofSeconds(10));
        assertEquals(1, worker.tick(UUID.randomUUID(), NOW.plusSeconds(30), NOW.plusSeconds(120)).unavailable());
        assertEquals("ACCEPTED", ledger.receipt(failed.id()).phase());
        assertEquals(NOW.plusSeconds(30), ledger.receipt(failed.id()).leaseUntil());
        assertEquals(1, worker.tick(UUID.randomUUID(), NOW.plusSeconds(30), NOW.plusSeconds(120)).prepared());
        assertEquals(List.of(failed.scope().captureDomain(), healthy.scope().captureDomain()), reads);
        assertFalse(captured.getAll().contains("private dispatch source token"));
    }

}
