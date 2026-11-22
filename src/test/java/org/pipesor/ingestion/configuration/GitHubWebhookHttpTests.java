package org.pipesor.ingestion.configuration;

import java.net.*;
import java.io.BufferedReader;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import com.nimbusds.jwt.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real servlet/HTTP/security/HMAC; only scope, secret custody and the durable-use-case boundary are simulated. */
class GitHubWebhookHttpTests {
    static final KeyPair RSA=keys();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final byte[] KEY="synthetic-hook-HTTP-key".getBytes(StandardCharsets.UTF_8);
    static final String PATH=GitHubWebhookConfiguration.PATH;
    static final String BODY="{\"action\":\"completed\",\"installation\":{\"id\":360},\"repository\":{\"id\":480,\"owner\":{\"id\":240}},\"workflow_run\":{\"id\":680,\"workflow_id\":590,\"run_attempt\":2,\"repository\":{\"id\":480,\"owner\":{\"id\":240}}}}";
    static final CaptureScope SCOPE=new CaptureScope("pilot",UUID.fromString("11111111-1111-4111-8111-111111111111"),"120","240","360","480","590","r1");
    static volatile boolean keyUnavailable;
    static ConfigurableApplicationContext context;static URI base;
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration","org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration","org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
    @Import({GitHubWebhookConfiguration.class,org.pipesor.ingestion.shared.security.PlatformSecurityConfiguration.class,WebConfiguration.class,OpenApiConfiguration.class})
    static class Application {
        @Bean CaptureHandler captureHandler(){return mock(CaptureHandler.class);}
        @Bean GitHubWorkflowWebhook webhook(CaptureHandler handler){return new GitHubWorkflowWebhook(new GitHubWorkflowWebhook.HookContext("pilot",SCOPE.tenantId(),"120"),()->{if(keyUnavailable)throw new IllegalStateException("private-key-error");return KEY;},source->new GitHubWorkflowWebhook.Authorization(SCOPE,Instant.now().plusSeconds(600)),Set.of("completed"),1024,handler);}
    }
    @BeforeAll static void start(){context=application().run();base=URI.create("http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort());}
    @AfterAll static void close(){context.close();}
    @BeforeEach void resetHandler(){keyUnavailable=false;var handler=context.getBean(CaptureHandler.class);reset(handler);when(handler.accept(any())).thenReturn(mock(CaptureReceipt.class));}
    static SpringApplicationBuilder application(){return application(true);}
    static final org.pipesor.ingestion.shared.security.PlatformTestIdentity IDENTITY=new org.pipesor.ingestion.shared.security.PlatformTestIdentity();
    static SpringApplicationBuilder application(boolean enabled){
        java.nio.file.Path keys;
        try{keys=java.nio.file.Files.createTempDirectory("webhook-keys");}catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        var settings=new ArrayList<>(IDENTITY.properties(keys));
        settings.addAll(GitHubTestSettings.properties(keys));
        settings.addAll(List.of("server.port=0","server.address=127.0.0.1","pipesor.github.webhook-enabled="+enabled,"springdoc.api-docs.enabled=true",
                "pipesor.github.limits.webhook-timeout=200ms","pipesor.github.limits.webhook-max-header-chars=512","pipesor.github.limits.webhook-max-in-flight=1","pipesor.github.limits.webhook-max-body-bytes=1024"));
        return new SpringApplicationBuilder(Application.class).profiles("local").initializers(c->org.springframework.boot.test.util.TestPropertyValues.of(settings.toArray(String[]::new)).applyTo(c));
    }
    HttpResponse<String> post(String body,String event,String signature,String... extra)throws Exception {
        var request=HttpRequest.newBuilder(base.resolve(PATH)).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").header("X-GitHub-Event",event).header("X-GitHub-Delivery","delivery-http");if(signature!=null)request.header("X-Hub-Signature-256",signature);for(int i=0;i<extra.length;i+=2)request.header(extra[i],extra[i+1]);
        try(var client=HttpClient.newHttpClient()){return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    }
    static String sign(String body)throws Exception{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY,"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));}
    @Test void vendorRequiresHmacButNoPlatformTokenAndAcknowledgesOnlyAfterUseCaseReturns()throws Exception {
        var response=post(BODY,"workflow_run",sign(BODY));assertEquals(204,response.statusCode(),response.body());assertEquals("",response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());verify(context.getBean(CaptureHandler.class)).accept(any());
    }
    @Test void signedPingAndUnselectedActionsAreIgnoredButChangedEventCannotHideSelectedRun()throws Exception {
        assertEquals(204,post("{\"zen\":\"synthetic\"}","ping",sign("{\"zen\":\"synthetic\"}")).statusCode());var ignored=BODY.replace("completed","in_progress");assertEquals(204,post(ignored,"workflow_run",sign(ignored)).statusCode());assertEquals(400,post(BODY,"ping",sign(BODY)).statusCode());verify(context.getBean(CaptureHandler.class),never()).accept(any());
    }
    @Test void rejectsMissingMalformedInvalidAndDuplicatedSignatures()throws Exception {
        assertEquals(403,post(BODY,"workflow_run",null).statusCode());assertEquals(403,post(BODY,"workflow_run","private-invalid").statusCode());assertEquals(403,post(BODY,"workflow_run","sha256="+"0".repeat(64)).statusCode());assertEquals(403,post(BODY,"workflow_run",sign(BODY),"X-Hub-Signature-256",sign(BODY)).statusCode());verify(context.getBean(CaptureHandler.class),never()).accept(any());
    }
    @Test void rejectsAmbiguousHeadersOversizeCompressedAndIncompatibleBodies()throws Exception {
        assertEquals(400,post(BODY,"workflow_run",sign(BODY),"X-GitHub-Event","ping").statusCode());var ping="{\"zen\":\"synthetic\"}";assertEquals(400,post(ping,"ping,workflow_run",sign(ping)).statusCode());assertEquals(400,post(BODY,"workflow_run",sign(BODY),"X-GitHub-Delivery","other").statusCode());assertEquals(415,post(BODY,"workflow_run",sign(BODY),"Content-Encoding","gzip").statusCode());assertEquals(400,post("private-invalid-json","ping",sign("private-invalid-json")).statusCode());var huge="x".repeat(2048);assertEquals(413,post(huge,"ping",sign(huge)).statusCode());verify(context.getBean(CaptureHandler.class),never()).accept(any());
    }
    @Test void chunkedBodiesStillEnforceBytesAndPreserveOriginalHmac()throws Exception {
        try(var client=HttpClient.newHttpClient()){
            for(var body:List.of(BODY,"x".repeat(2048))){
                var builder=HttpRequest.newBuilder(base.resolve(PATH)).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json").header("X-GitHub-Event","workflow_run").header("X-GitHub-Delivery","chunked-delivery").header("X-Hub-Signature-256",sign(body));
                var request=builder.POST(HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))).build();
                assertEquals(body.length()>1024?413:204,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }
        assertEquals(204,post(BODY,"workflow_run",sign(BODY)).statusCode());verify(context.getBean(CaptureHandler.class),times(2)).accept(any());
    }
    @Test void incompatibleCharsetAndOversizedHeadersNeverReachCapture()throws Exception {
        try(var client=HttpClient.newHttpClient()){
            var request=HttpRequest.newBuilder(base.resolve(PATH)).header("Content-Type","application/json;charset=iso-8859-1").header("X-GitHub-Event","workflow_run").header("X-GitHub-Delivery","charset").header("X-Hub-Signature-256",sign(BODY)).POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
            assertEquals(415,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(400,post(BODY,"x".repeat(513),sign(BODY)).statusCode());verify(context.getBean(CaptureHandler.class),never()).accept(any());
    }
    @Test void dependencyScopeConflictAndUncertainCommitNeverBecomeSuccessfulAck()throws Exception {
        var handler=context.getBean(CaptureHandler.class);
        for(var entry:Map.of(CaptureFailure.Reason.UNAVAILABLE,503,CaptureFailure.Reason.SCOPE_CHANGED,403,CaptureFailure.Reason.CONFLICT,409).entrySet()){
            doThrow(new CaptureFailure(entry.getKey())).when(handler).accept(any());assertEquals(entry.getValue(),post(BODY,"workflow_run",sign(BODY)).statusCode());
        }
        keyUnavailable=true;var response=post(BODY,"workflow_run",sign(BODY));assertEquals(503,response.statusCode());assertFalse(response.body().contains("private"));
    }
    @Test void completedTooLateCannotReturn204()throws Exception {
        doAnswer(inv->{Thread.sleep(450);return mock(CaptureReceipt.class);}).when(context.getBean(CaptureHandler.class)).accept(any());assertEquals(503,post(BODY,"workflow_run",sign(BODY)).statusCode());Thread.sleep(500);
    }
    @Test void processingTimeoutRetainsCapacityUntilTheUnderlyingCaptureReturns()throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var returned=new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv->{entered.countDown();try{assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return mock(CaptureReceipt.class);}finally{returned.countDown();}}).when(context.getBean(CaptureHandler.class)).accept(any());
        try(var threads=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var first=threads.submit(()->post(BODY,"workflow_run",sign(BODY)));assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(503,first.get(3,java.util.concurrent.TimeUnit.SECONDS).statusCode());
            assertEquals(503,post(BODY,"workflow_run",sign(BODY)).statusCode());verify(context.getBean(CaptureHandler.class),times(1)).accept(any());
            release.countDown();assertTrue(returned.await(2,java.util.concurrent.TimeUnit.SECONDS));
            doReturn(mock(CaptureReceipt.class)).when(context.getBean(CaptureHandler.class)).accept(any());
            assertTimeoutPreemptively(Duration.ofSeconds(2),()->{while(post(BODY,"workflow_run",sign(BODY)).statusCode()==503)Thread.sleep(10);});
            assertEquals(204,post(BODY,"workflow_run",sign(BODY)).statusCode());
        }finally{release.countDown();}
    }
    @Test void rejectedRequestsNeverConsumeOrLeakCapacity()throws Exception {
        for(int i=0;i<3;i++){
            assertEquals(403,post(BODY,"workflow_run","private-invalid").statusCode());
            var huge="x".repeat(2048);assertEquals(413,post(huge,"workflow_run",sign(huge)).statusCode());
            assertEquals(204,post(BODY,"workflow_run",sign(BODY)).statusCode());
        }
    }
    @Test void slowIncompleteBodyHitsTotalRequestTimeout()throws Exception {
        try(var socket=new Socket("127.0.0.1",base.getPort())){
            socket.setSoTimeout(2000);var text="POST "+PATH+" HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nX-Hub-Signature-256: sha256="+"0".repeat(64)+"\r\nX-GitHub-Event: workflow_run\r\nX-GitHub-Delivery: slow-body\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{";
            socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();var line=new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();assertNotNull(line);assertTrue(line.contains("503"),line);
        }
        verify(context.getBean(CaptureHandler.class),never()).accept(any());
    }
    @Test void generatedOpenApiDescribesHmacOnlyWhenTheServletIsEnabled()throws Exception {
        try(var client=HttpClient.newHttpClient()){
            var response=client.send(HttpRequest.newBuilder(base.resolve("/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
            var schema=JSON.readTree(response.body());var operation=schema.get("paths").get(PATH).get("post");assertEquals(0,operation.get("security").size());assertTrue(operation.get("parameters").toString().contains("X-Hub-Signature-256"));assertTrue(operation.get("parameters").toString().contains("exact transmitted bytes"));
            var signatureHeader=java.util.stream.StreamSupport.stream(operation.get("parameters").spliterator(),false).filter(p->p.get("name").asString().equals("X-Hub-Signature-256")).findFirst().orElseThrow();
            var signaturePattern=java.util.regex.Pattern.compile(signatureHeader.get("schema").get("pattern").asString());
            assertTrue(signaturePattern.matcher("sha256="+"0".repeat(64)).find());assertFalse(signaturePattern.matcher("prefix-sha256="+"0".repeat(64)+"-suffix").find());
            assertEquals(Set.of("204","400","403","409","413","415","503"),new HashSet<>(operation.get("responses").propertyNames()));assertFalse(response.body().contains("synthetic-hook-HTTP-key"));assertFalse(schema.get("components").get("securitySchemes").toString().contains("apiKey"));
            try(var disabled=application(false).run()){
                var uri=URI.create("http://127.0.0.1:"+((WebServerApplicationContext)disabled).getWebServer().getPort()+"/v3/api-docs");
                var hidden=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,hidden.statusCode(),hidden.body());var paths=JSON.readTree(hidden.body()).get("paths");assertTrue(paths==null||!paths.has(PATH));
            }
        }
    }
    static KeyPair keys(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
    @Test void wrongMethodsAndFrameworkEndpointsAreNotPublic()throws Exception {
        try(var client=HttpClient.newHttpClient()){
            assertEquals(403,client.send(HttpRequest.newBuilder(base.resolve(PATH)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            for(var path:List.of("/.well-known/oauth-protected-resource","/logout","/api/v1/github/repositories?limit=1")){var response=client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),HttpResponse.BodyHandlers.ofString());assertTrue(response.statusCode()==401||response.statusCode()==403,response.body());}
        }
    }
}
