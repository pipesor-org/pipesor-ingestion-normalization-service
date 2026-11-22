package org.pipesor.ingestion.capture.infrastructure.persistence.jpa;

import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.commandhandlers.PublicationWorker;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.transaction.PlatformTransactionManager;
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
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.StageName.TESTING;

/** Real PostgreSQL 17.11/Flyway/JPA; only source authorization, protection and broker are simulated. */
@ExtendWith(OutputCaptureExtension.class)
class PublicationWorkerTests {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Instant SOURCE_TIME = Instant.parse("2026-11-08T11:59:00.999999999Z");
    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static CaptureLedger ledger;
    private static CaptureHandler handler;
    private static MutableClock clock;
    private static ScopeDouble authorization;
    private static BrokerDouble broker;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
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
    static void start() {
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
    @AfterEach void stop() { if (context != null) { context.close(); } if (postgres != null) { postgres.stop(); } }
    @BeforeEach void reset() throws Exception {
        start();
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
    private CaptureFailure failure(CaptureFailure.Reason reason, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(CaptureFailure.class, action); assertEquals(reason, failure.reason()); assertNull(failure.getCause()); return failure;
    }


    private PublicationReadyReceipts ready() {
        return new JpaPublicationReadyReceipts(SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class)),context.getBean(PlatformTransactionManager.class));
    }
    private PublicationWorker worker(PublicationReadyReceipts selection) {
        return new PublicationWorker(selection,handler,clock,10,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(5));
    }
    @Test void publishesTwoFrozenOutputsAcrossPassesThenBecomesIdle() {
        var receipt=prepared(true); var worker=worker(ready());
        assertEquals(1,worker.tick().published()); assertEquals("PREPARED",ledger.receipt(receipt.id()).phase());
        assertEquals(1,worker.tick().published()); assertEquals("PUBLISHED",ledger.receipt(receipt.id()).phase());
        assertEquals(0,worker.tick().attempted());
        assertEquals(2,broker.sent.size());
        assertEquals(Set.of(receipt.snapshotMessageId(),receipt.evidenceMessageId()),broker.sent.stream().map(PublicationOutput::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("original-correlation","original-correlation"),broker.sent.stream().map(PublicationOutput::correlationId).toList());
    }
    @Test void brokerFailureDefersOnlyThatOutputAndRetryReusesExactBytesAndId(CapturedOutput output) {
        var receipt=prepared(false); var worker=worker(ready()); broker.fail=true;
        assertEquals(1,worker.tick().unavailable()); assertEquals(0,worker.tick().attempted());
        broker.fail=false; clock.now=NOW.plusSeconds(10); assertEquals(1,worker.tick().published());
        assertEquals(broker.sent.get(0).id(),broker.sent.get(1).id()); assertArrayEquals(broker.sent.get(0).body(),broker.sent.get(1).body());
        assertEquals("PUBLISHED",ledger.receipt(receipt.id()).phase());
        assertFalse(output.getAll().contains("private-provider-token"));assertFalse(output.getAll().contains("synthetic-secret"));
    }
    @Test void scannerSelectsOnlyDueOriginsAndExpiredClaimsWithABound() {
        var unprepared=accept(); assertTrue(ready().read(NOW,10,null).isEmpty());
        var first=prepared(false); var second=prepared(false);
        assertEquals(1,ready().read(NOW,1,null).size()); assertEquals(2,ready().read(NOW,10,null).size());
        var claimed=ledger.claimOutput(first.observationId(),UUID.randomUUID(),NOW.plusSeconds(20),NOW).orElseThrow();
        assertEquals(List.of(second.id()),ready().read(NOW,10,null));
        assertEquals(2,ready().read(NOW.plusSeconds(20),10,null).size());
        ledger.retry(claimed,NOW.plusSeconds(40),NOW);
        assertEquals(List.of(second.id()),ready().read(NOW.plusSeconds(30),10,null));
        assertEquals(2,ready().read(NOW.plusSeconds(40),10,null).size());
        assertEquals("ACCEPTED",ledger.receipt(unprepared.id()).phase());
    }
    @Test void oneFailedReceiptDoesNotStopTheOtherReadyReceipt() {
        prepared(false); prepared(false); var calls=new java.util.concurrent.atomic.AtomicInteger();
        var mixed=new CaptureHandler(ledger,authorization,output -> { if(calls.getAndIncrement()==0)throw new IllegalStateException("private-token"); broker.publish(output); },clock);
        var result=new PublicationWorker(ready(),mixed,clock,10,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(5)).tick();
        assertEquals(2,result.attempted());assertEquals(1,result.unavailable());assertEquals(1,result.published());
    }
    @Test void scopeLossBlocksWithoutPublication() {
        var receipt=prepared(false);authorization.decision=CurrentMachineScope.Decision.CHANGED_OR_LOST;
        assertEquals(1,worker(ready()).tick().blocked());assertTrue(broker.sent.isEmpty());
        assertEquals("BLOCKED",ledger.receipt(receipt.id()).phase());assertTrue(ready().read(NOW,10,null).isEmpty());
    }
    @Test void concurrentWorkersUseTheExistingExclusiveOutputClaim() throws Exception {
        var receipt=prepared(false);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var guarded=new CaptureHandler(ledger,authorization,output -> {
            outside();entered.countDown();try { assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)); }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}broker.publish(output);
        },clock);
        var worker=new PublicationWorker((now,limit,after)->List.of(receipt.id()),guarded,clock,1,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(5));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->{return worker.tick();});assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var second=worker.tick();assertEquals(1,second.unclaimed());release.countDown();assertEquals(1,first.get().published());
        } finally { release.countDown(); }
        assertEquals(1,broker.sent.size());
    }
    @Test void scannerRejectsAmbientTransactionsAndInvalidLimits() {
        failure(INVALID_INPUT,()->ready().read(NOW,0,null));
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> failure(INVALID_INPUT,()->ready().read(NOW,1,null)));
    }
    @Test void boundedPassStopsNewStartsAfterTimeBudget() {
        prepared(false);prepared(false);
        var slow=new CaptureHandler(ledger,authorization,output -> {try {Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();}broker.publish(output);},clock);
        var worker=new PublicationWorker(ready(),slow,clock,10,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofMillis(10));
        var result=worker.tick();assertTrue(result.budgetExhausted());assertTrue(result.attempted()<=1);assertEquals(result.attempted(),broker.sent.size());
    }

    @Test void roundRobinReachesAnotherReceiptAfterAPoisonedDueOrigin() {
        prepared(false);prepared(false);var ordered=ready().read(NOW,10,null);var poison=ledger.receipt(ordered.getFirst());var healthy=ordered.getLast();
        var currentScope=(CurrentMachineScope) expected->expected.equals(poison.scope())?CurrentMachineScope.Decision.UNAVAILABLE:CurrentMachineScope.Decision.AUTHORIZED;
        var bounded=new PublicationWorker(ready(),new CaptureHandler(ledger,currentScope,broker,clock),clock,1,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(5));
        assertEquals(1,bounded.tick().unavailable());assertEquals(1,bounded.tick().published());
        assertEquals("PUBLISHED",ledger.receipt(healthy).phase());assertEquals("PREPARED",ledger.receipt(poison.id()).phase());
        assertEquals(1,bounded.tick().unavailable());assertEquals(1,broker.sent.size());
    }
    @Test void integrityFailureIsUnavailableRatherThanClaimContention() {
        var broken=org.mockito.Mockito.mock(CaptureHandler.class);
        org.mockito.Mockito.when(broken.publishNext(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenThrow(new CaptureFailure(CONFLICT));
        var result=new PublicationWorker((now,limit,after)->List.of(UUID.randomUUID()),broken,clock,1,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(5)).tick();
        assertEquals(1,result.unavailable());assertEquals(0,result.stale());
    }
    @Test void interruptionStopsNewStartsAfterAnInFlightSendReturns() {
        prepared(false);prepared(false);
        var interrupting=new CaptureHandler(ledger,authorization,output->{broker.publish(output);Thread.currentThread().interrupt();},clock);
        try {
            var result=new PublicationWorker(ready(),interrupting,clock,10,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(5)).tick();
            assertEquals(1,result.attempted());assertTrue(result.interrupted());assertEquals(1,broker.sent.size());
        } finally {Thread.interrupted();}
    }

    @Test void managedStopPreventsSecondSendEvenWhenProviderClearsInterrupt() throws Exception {
        prepared(false);prepared(false);var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var finished=new java.util.concurrent.CountDownLatch(1);
        var blocking=new CaptureHandler(ledger,authorization,output->{
            entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            broker.publish(output);finished.countDown();
        },clock);
        var runner=new org.pipesor.ingestion.capture.infrastructure.scheduling.PublicationRunner(
                new PublicationWorker(ready(),blocking,clock,10,Duration.ofSeconds(30),Duration.ofSeconds(10),Duration.ofSeconds(5)),
                Duration.ofSeconds(30),Duration.ofMillis(10));
        try {
            runner.start();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));runner.stop();release.countDown();assertTrue(finished.await(3,java.util.concurrent.TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(runner.lastResult()==null)Thread.sleep(5);});
            assertEquals(1,runner.lastResult().attempted());assertTrue(runner.lastResult().interrupted());assertEquals(1,broker.sent.size());
        }finally{release.countDown();runner.stop();}
    }
    static void outside(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
}
