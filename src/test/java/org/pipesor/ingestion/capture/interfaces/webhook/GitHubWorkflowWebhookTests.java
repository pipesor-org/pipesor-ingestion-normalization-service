package org.pipesor.ingestion.capture.interfaces.webhook;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.infrastructure.persistence.jpa.CaptureJpaEntities;
import org.pipesor.ingestion.configuration.CapturePersistenceConfiguration;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.ConnectionJpaEntities;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.system.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;
import static org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.*;

/** PostgreSQL and HMAC are real; scope and secret custody are explicitly simulated. No public endpoint. */
@ExtendWith(OutputCaptureExtension.class)
class GitHubWorkflowWebhookTests {
    private static final Instant NOW=Instant.parse("2026-11-10T12:00:00Z");
    private static final byte[] KEY="synthetic-webhook-secret-é".getBytes(StandardCharsets.UTF_8);
    private static final AtomicInteger IDS=new AtomicInteger(100);
    private static volatile CurrentMachineScope.Decision decision=CurrentMachineScope.Decision.AUTHORIZED;
    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static CaptureHandler handler;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @EntityScan(basePackageClasses={CaptureJpaEntities.class,ConnectionJpaEntities.class})
    @Import(CapturePersistenceConfiguration.class)
    static class TestConfiguration {
        @Bean Clock clock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean CurrentMachineScope scope(){return expected->{outside();return decision;};}
        @Bean BrokerPublisher broker(){return output->{fail("Webhook acceptance must not publish");};}
    }
    @BeforeAll static void start(){
        postgres=new PostgreSQLContainer("postgres:17.11");postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).defaultSchema("ingestion").schemas("ingestion").load().migrate();
        context=new SpringApplicationBuilder(TestConfiguration.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),
                "--spring.datasource.password="+postgres.getPassword(),"--ingestion.capture.enabled=true");
        handler=context.getBean(CaptureHandler.class);
    }
    @AfterAll static void stop(){if(context!=null)context.close();if(postgres!=null)postgres.stop();}
    @BeforeEach void reset(){decision=CurrentMachineScope.Decision.AUTHORIZED;}
    private static void outside(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
    private record Fixture(CaptureScope scope,String run,byte[] body){}
    private Fixture fixture(){
        int n=IDS.incrementAndGet();var scope=new CaptureScope("hook-"+UUID.randomUUID(),UUID.randomUUID(),"42","2"+n,"3"+n,"4"+n,"5"+n,"scope-"+n);
        var run=new BigInteger("900719925474099399999999").add(BigInteger.valueOf(n)).toString();
        var repo="{\"id\":"+scope.repositoryId()+",\"owner\":{\"id\":"+scope.accountId()+"}}";
        var body=("{\"action\":\"completed\",\"installation\":{\"id\":"+scope.installationId()+"},\"repository\":"+repo+
                ",\"workflow_run\":{\"id\":"+run+",\"workflow_id\":"+scope.workflowId()+",\"run_attempt\":2,\"repository\":"+repo+"},\"sender\":{\"login\":\"private-source-é😀\"}}").getBytes(StandardCharsets.UTF_8);
        return new Fixture(scope,run,body);
    }
    private HookContext hook(Fixture f){return new HookContext(f.scope.captureDomain(),f.scope.tenantId(),f.scope.appId());}
    private GitHubWorkflowWebhook ingress(Fixture f){return new GitHubWorkflowWebhook(hook(f),()->{outside();return KEY;},source->{outside();return new Authorization(f.scope,NOW.plusSeconds(3600));},Set.of("completed"),65536,handler);}
    private static String sign(byte[] body){return sign(body,KEY);}
    private static String sign(byte[] body,byte[] key){
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(body));}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    private Object scalar(String sql,Object... args)throws Exception{
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var s=c.prepareStatement(sql)){
            for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(var r=s.executeQuery()){assertTrue(r.next());return r.getObject(1);}
        }
    }
    @Test void acceptsVerifiedBytesDurablyAndDeduplicatesChangedUnsignedDeliveryHeaders(CapturedOutput logs)throws Exception{
        var f=fixture();var ingress=ingress(f);var signature=sign(f.body);
        var first=ingress.accept(f.body,signature,"workflow_run","delivery-1","corr-original");
        var replay=ingress.accept(f.body,signature,"workflow_run","delivery-2","corr-later");
        assertEquals(first.id(),replay.id());assertEquals(f.run,first.runId());assertEquals(2,first.runAttempt());
        assertEquals(f.scope,first.scope());assertEquals("corr-original",replay.correlationId());assertEquals("ACCEPTED",first.phase());
        assertEquals(2L,scalar("select count(*) from ingestion.capture_delivery_aliases where receipt_id=?",first.id()));
        assertFalse(scalar("select row_to_json(r)::text from ingestion.capture_receipts r where id=?",first.id()).toString().contains("private-source"));
        assertFalse(logs.getAll().contains("private-source"));assertFalse(ingress.toString().contains("synthetic-webhook-secret"));
    }
    @Test void concurrentAuthenticatedRedeliveryCommitsOneReceiptAndAllAliases()throws Exception{
        var f=fixture();var ingress=ingress(f);var signature=sign(f.body);var barrier=new CyclicBarrier(3);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<UUID>>();
            for(int i=0;i<3;i++){int index=i;futures.add(workers.submit(()->{barrier.await();return ingress.accept(f.body,signature,"workflow_run","delivery-"+index,"corr-"+index).id();}));}
            var id=futures.getFirst().get();for(var future:futures)assertEquals(id,future.get());
            assertEquals(3L,scalar("select count(*) from ingestion.capture_delivery_aliases where receipt_id=?",id));
        }
    }
    @Test void sameDeliveryWithDifferentAuthenticatedBodyConflictsWithoutAnotherReceipt()throws Exception{
        var f=fixture();var ingress=ingress(f);ingress.accept(f.body,sign(f.body),"workflow_run","same","corr");
        var changed=new String(f.body,StandardCharsets.UTF_8).replace("private-source","different-private-source").getBytes(StandardCharsets.UTF_8);
        failure(CONFLICT,()->ingress.accept(changed,sign(changed),"workflow_run","same","corr"));
        assertEquals(1L,scalar("select count(*) from ingestion.capture_receipts where capture_domain=?",f.scope.captureDomain()));
    }
    @ParameterizedTest @ValueSource(strings={"missing","wrong-key","tampered","sha1","short","extra-header"})
    void rejectsInvalidSignatureWithoutScopeResolutionOrPersistence(String scenario)throws Exception{
        var f=fixture();var resolved=new AtomicInteger();var ingress=new GitHubWorkflowWebhook(hook(f),()->KEY,s->{resolved.incrementAndGet();return new Authorization(f.scope,NOW.plusSeconds(300));},Set.of("completed"),65536,handler);
        var signature=switch(scenario){case "missing"->null;case "wrong-key"->sign(f.body,"other-key".getBytes(StandardCharsets.UTF_8));case "sha1"->"sha1="+"0".repeat(40);case "short"->"sha256=01";case "extra-header"->sign(f.body)+", "+sign(f.body);default->sign(f.body);};
        var bytes=scenario.equals("tampered")?Arrays.copyOf(f.body,f.body.length-1):f.body;
        var rejected=assertThrowsExactly(InvalidSignature.class,()->ingress.accept(bytes,signature,"workflow_run","delivery","corr"));assertNull(rejected.getCause());assertEquals(0,resolved.get());
        assertEquals(0L,scalar("select count(*) from ingestion.capture_receipts where capture_domain=?",f.scope.captureDomain()));
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","trailing","mismatch-repository","mismatch-owner","fractional-attempt","unknown-action","missing-installation"})
    void signedMalformedOrCrossedPayloadCannotBeAccepted(String scenario){
        var f=fixture();var text=new String(f.body,StandardCharsets.UTF_8);
        text=switch(scenario){case "duplicate"->text.replace("\"action\":","\"action\":\"requested\",\"action\":");case "trailing"->text+" {}";
            case "mismatch-repository"->text.replaceFirst("\"id\":"+f.scope.repositoryId(),"\"id\":999");case "mismatch-owner"->text.replaceFirst("\"id\":"+f.scope.accountId(),"\"id\":999");
            case "fractional-attempt"->text.replace("\"run_attempt\":2","\"run_attempt\":2.5");case "unknown-action"->text.replace("completed","requested");default->text.replace("\"installation\"","\"missing_installation\"");};
        var bytes=text.getBytes(StandardCharsets.UTF_8);failure(INVALID_INPUT,()->ingress(f).accept(bytes,sign(bytes),"workflow_run","d","c"));
    }
    @ParameterizedTest @ValueSource(strings={"tenant","domain","app","account","installation","repository","workflow"})
    void independentlyResolvedScopeCannotCrossHookOrSignedSource(String crossing){
        var f=fixture();var s=f.scope;
        var changed=new CaptureScope(crossing.equals("domain")?"other-domain":s.captureDomain(),crossing.equals("tenant")?UUID.randomUUID():s.tenantId(),
                crossing.equals("app")?"99":s.appId(),crossing.equals("account")?"99":s.accountId(),crossing.equals("installation")?"99":s.installationId(),
                crossing.equals("repository")?"99":s.repositoryId(),crossing.equals("workflow")?"99":s.workflowId(),s.scopeRevision());
        var ingress=new GitHubWorkflowWebhook(hook(f),()->KEY,source->new Authorization(changed,NOW.plusSeconds(300)),Set.of("completed"),65536,handler);
        failure(SCOPE_CHANGED,()->ingress.accept(f.body,sign(f.body),"workflow_run","d","c"));
    }
    @Test void signatureIsCheckedBeforeParsingAndProviderFailuresNeverLeakPrivateCauses(){
        var f=fixture();var ingress=new GitHubWorkflowWebhook(hook(f),()->{throw new IllegalStateException("private-secret-custody");},s->null,Set.of("completed"),1000,handler);
        failure(UNAVAILABLE,()->ingress.accept("not-json".getBytes(StandardCharsets.UTF_8),"sha256="+"0".repeat(64),"workflow_run","d","c"));
        var badResolver=new GitHubWorkflowWebhook(hook(f),()->KEY,s->{throw new IllegalStateException("private-scope");},Set.of("completed"),65536,handler);
        failure(UNAVAILABLE,()->badResolver.accept(f.body,sign(f.body),"workflow_run","d","c"));
    }
    @Test void enforcesBodyBudgetEventScopeExpiryAndAmbientTransactionRejection()throws Exception{
        var f=fixture();var small=new GitHubWorkflowWebhook(hook(f),()->KEY,s->null,Set.of("completed"),f.body.length-1,handler);
        failure(INVALID_INPUT,()->small.accept(f.body,sign(f.body),"workflow_run","d","c"));
        failure(INVALID_INPUT,()->ingress(f).accept(f.body,sign(f.body),"workflow_job","d","c"));
        decision=CurrentMachineScope.Decision.CHANGED_OR_LOST;failure(SCOPE_CHANGED,()->ingress(f).accept(f.body,sign(f.body),"workflow_run","d","c"));
        decision=CurrentMachineScope.Decision.UNAVAILABLE;failure(UNAVAILABLE,()->ingress(f).accept(f.body,sign(f.body),"workflow_run","d","c"));
        decision=CurrentMachineScope.Decision.AUTHORIZED;
        var expired=new GitHubWorkflowWebhook(hook(f),()->KEY,s->new Authorization(f.scope,NOW),Set.of("completed"),65536,handler);
        failure(UNAVAILABLE,()->expired.accept(f.body,sign(f.body),"workflow_run","d","c"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{failure(INVALID_STATE,()->ingress(f).accept(f.body,sign(f.body),"workflow_run","d","c"));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals(0L,scalar("select count(*) from ingestion.capture_receipts where capture_domain=?",f.scope.captureDomain()));
    }
    @Test void selectedEventsCannotBeHiddenByAnUnsignedHeaderAndIgnoredEventsNeedValidHmac() {
        var f=fixture();var ingress=ingress(f);
        failure(INVALID_INPUT,()->ingress.receive(f.body,sign(f.body),"ping","d","c"));
        var ping="{\"zen\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8);
        assertTrue(ingress.receive(ping,sign(ping),"ping","d","c").isEmpty());
        assertThrowsExactly(InvalidSignature.class,()->ingress.receive(ping,"sha256="+"0".repeat(64),"ping","d","c"));
        var unselected=new String(f.body,StandardCharsets.UTF_8).replace("completed","in_progress").getBytes(StandardCharsets.UTF_8);
        assertTrue(ingress.receive(unselected,sign(unselected),"workflow_run","d","c").isEmpty());
    }
    private void failure(CaptureFailure.Reason reason,org.junit.jupiter.api.function.Executable action){
        var failure=assertThrowsExactly(CaptureFailure.class,action);assertEquals(reason,failure.reason());assertNull(failure.getCause());assertFalse(failure.toString().contains("private-"));
    }
}
