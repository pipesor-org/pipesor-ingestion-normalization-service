package org.pipesor.ingestion.capture.infrastructure.github;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.springframework.boot.test.system.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** API calls use a real local HTTP server. The HTTPS download response is simulated separately. */
@ExtendWith(OutputCaptureExtension.class)
class GitHubMachineTransportTests {
    static final Instant NOW=Instant.parse("2026-11-10T12:00:00Z");
    static final String APP="120",ACCOUNT="240",INSTALL="360",REPO="480000000000000000001",WORKFLOW="590000000000000000002",RUN="680000000000000000003";
    final CaptureScope scope=new CaptureScope("pilot",UUID.fromString("11111111-1111-4111-8111-111111111111"),APP,ACCOUNT,INSTALL,REPO,WORKFLOW,"scope-r2");
    final GitHubMachineCapture.Request request=new GitHubMachineCapture.Request(scope,RUN,3,"corr-machine-92");
    HttpServer server;ExecutorService executor;URI root;HttpClient real;
    AtomicInteger runReads=new AtomicInteger(),calls=new AtomicInteger(),mintCalls=new AtomicInteger(),scopeCalls=new AtomicInteger();
    volatile String mode="ok",mintBody;volatile boolean fenced;final AtomicInteger afterFenceChecks=new AtomicInteger();volatile Instant now=NOW;volatile int loseScopeAt=Integer.MAX_VALUE;volatile HttpRequest download;volatile byte[] archive;
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}};
    static final JsonMapper JSON=JsonMapper.builder().build();
    @BeforeEach void start() throws Exception {
        real=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        executor=Executors.newVirtualThreadPerTaskExecutor();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
        server.createContext("/",e->{
            calls.incrementAndGet();String path=e.getRequestURI().getPath();boolean mint=path.endsWith("/access_tokens");
            assertEquals("Bearer "+(path.startsWith("/app")?"private-app-jwt":"private-install-token"),e.getRequestHeaders().getFirst("Authorization"));
            assertEquals("2026-03-10",e.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
            assertEquals(mint?"POST":"GET",e.getRequestMethod());
            if(mint){mintCalls.incrementAndGet();mintBody=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(mode.equals("lost-mint")){e.close();return;}}
            int status=mint?201:200;String body;
            if(path.equals("/app"))body="{\"id\":"+(mode.equals("wrong-app")?"121":APP)+"}";
            else if(path.equals("/app/installations/"+INSTALL))body="{\"id\":"+INSTALL+",\"app_id\":"+APP+",\"account\":{\"id\":"+ACCOUNT+"},\"suspended_at\":"+(mode.equals("suspended")?"\"2026-11-01T00:00:00Z\"":"null")+"}";
            else if(mint)body="{\"token\":\"private-install-token\",\"expires_at\":\"2026-11-10T12:03:00Z\",\"permissions\":{\"actions\":\""+(mode.equals("write")?"write":"read")+"\",\"metadata\":\"read\"}}";
            else if(path.equals("/installation/repositories"))body="{\"total_count\":"+(mode.equals("wide-token")?2:1)+",\"repositories\":["+repo()+"]}";
            else if(path.equals("/repos/org/project")){fenced=true;body=mode.equals("replacement")?repo().replace(REPO,"999"):repo();}
            else if(path.endsWith("/jobs/701/logs")||path.endsWith("/attempts/3/logs")){status=302;body="";e.getResponseHeaders().add("Location",mode.equals("evil-download")?"https://evil.example/log?private-link":"https://logs.example/log?private-link");}
            else if(path.endsWith("/jobs/701"))body=job("701",1);
            else if(path.endsWith("/jobs")) {
                int page=e.getRequestURI().getRawQuery().contains("page=2")?2:1;
                body="{\"total_count\":2,\"jobs\":["+job(page==1||mode.equals("duplicate-job")?"701":"702",page)+"]}";
                if(mode.equals("expire"))now=NOW.plusSeconds(300);
            } else {
                int reads=runReads.incrementAndGet();body=run();
                if(reads>1&&mode.equals("changed-updated"))body=body.replace("11:03:00.987654321Z","11:04:00.987654321Z");
                if(reads>1&&mode.equals("changed-status"))body=body.replace("completed","in_progress");
                if(reads>1&&mode.equals("changed-sha"))body=body.replace("a".repeat(40),"b".repeat(40));
                if(download!=null&&mode.equals("changed-after-log"))body=body.replace("11:03:00.987654321Z","11:04:00.987654321Z");
            }
            if(mode.equals("rate")&&path.equals("/installation/repositories")){status=403;body="private-rate-details";}
            if(mode.equals("malformed")&&path.equals("/installation/repositories"))body="private-unparseable";
            if(mode.equals("trailing")&&path.equals("/app"))body+=" {}";
            if(mint&&mode.equals("missing-permissions"))body="{\"token\":\"private-install-token\",\"expires_at\":\"2026-11-10T12:03:00Z\"}";
            if(mint&&mode.equals("expired-token"))body=body.replace("12:03:00Z","11:59:00Z");
            var bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);
            try{if(mode.equals("timeout"))Thread.sleep(500);e.getResponseBody().write(bytes);}catch(InterruptedException x){Thread.currentThread().interrupt();}catch(java.io.IOException cancelled){}finally{e.close();}
        });
        server.start();root=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop(){server.stop(0);executor.close();real.close();}
    String repo(){return "{\"id\":"+REPO+",\"full_name\":\"org/project\",\"owner\":{\"id\":"+(mode.equals("wrong-owner")?"241":ACCOUNT)+"}}";}
    String run(){return "{\"id\":"+RUN+",\"workflow_id\":"+(mode.equals("wrong-workflow")?"591":WORKFLOW)+",\"run_attempt\":"+(mode.equals("wrong-attempt")?4:3)+",\"repository\":"+repo()+",\"head_branch\":\"release/candidate\",\"head_sha\":\""+"a".repeat(40)+"\",\"status\":\"completed\",\"conclusion\":\"failure\",\"created_at\":\"2026-11-10T11:00:00.123456789Z\",\"updated_at\":\"2026-11-10T11:03:00.987654321Z\",\"run_started_at\":\"2026-11-10T11:01:00Z\"}";}
    String job(String id,int number){return "{\"id\":"+id+",\"run_id\":"+(mode.equals("wrong-job-run")?"681":RUN)+",\"run_attempt\":3,\"name\":\"job-"+number+"\",\"status\":\"completed\",\"conclusion\":\"failure\",\"started_at\":null,\"completed_at\":null,\"steps\":[{\"number\":"+number+",\"name\":\"step-"+number+"\",\"status\":\"completed\",\"conclusion\":\"failure\",\"started_at\":null,\"completed_at\":null}]}";}
    GitHubMachineTransport adapter(int bytes,Duration budget,boolean fakeDownload){return adapter(bytes,budget,fakeDownload,null);}
    GitHubMachineTransport adapter(int bytes,Duration budget,boolean fakeDownload,GitHubMachineTransport.ArchiveLimits limits){
        HttpClient client=real;
        if(fakeDownload){
            client=mock(HttpClient.class);when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
            when(client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{
                HttpRequest req=inv.getArgument(0);
                if(req.uri().getScheme().equals("https")){
                    download=req;var result=mock(HttpResponse.class);when(result.statusCode()).thenReturn(200);when(result.body()).thenReturn(archive!=null?archive:mode.equals("invalid-utf8")?new byte[]{(byte)0xc3,0x28}:"private-raw-log α😀\nline2".getBytes(StandardCharsets.UTF_8));
                    var headers=new HashMap<String,List<String>>();headers.put("Content-Type",List.of(archive!=null?"application/zip":mode.equals("non-utf8-charset")?"text/plain;charset=iso-8859-1":"text/plain;charset=UTF-8"));if(mode.equals("encoded-log"))headers.put("Content-Encoding",List.of("gzip"));when(result.headers()).thenReturn(HttpHeaders.of(headers,(a,b)->true));return CompletableFuture.completedFuture(result);
                }
                return real.sendAsync(req,inv.getArgument(1));
            });
        }
        return new GitHubMachineTransport(client,root,s->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());if(mode.equals("expire-final-scope")&&fenced&&afterFenceChecks.incrementAndGet()>=2)now=NOW.plusSeconds(300);return scopeCalls.incrementAndGet()>=loseScopeAt?CurrentMachineScope.Decision.CHANGED_OR_LOST:CurrentMachineScope.Decision.AUTHORIZED;},()->new GitHubMachineTransport.AppJwt(APP,"private-app-jwt",NOW.plusSeconds(30)),APP,"2026-03-10",budget,1,4,5,bytes,1024,limits,Set.of("logs.example"),clock);
    }
    GitHubMachineTransport adapter(){return adapter(16000,Duration.ofSeconds(3),false);}
    @Test void mintsOnlyReadOnlySingleRepositoryTokenAndPreservesAttemptJobsAndTimestamps(CapturedOutput output){
        var result=adapter().readRun(request);var sent=JSON.readTree(mintBody);
        assertEquals(REPO,sent.get("repository_ids").get(0).bigIntegerValue().toString());assertEquals(1,sent.get("repository_ids").size());assertEquals("read",sent.get("permissions").get("actions").asString());assertEquals(2,sent.get("permissions").size());
        assertEquals(request,result.request());assertEquals("release/candidate",result.branch());assertEquals(2,result.jobs().size());assertEquals("702",result.jobs().get(1).id());assertEquals(2,result.jobs().get(1).steps().getFirst().number());assertEquals(Instant.parse("2026-11-10T11:00:00.123456789Z"),result.createdAt());assertEquals(Instant.parse("2026-11-10T11:03:00.987654321Z"),result.updatedAt());assertEquals(1,mintCalls.get());assertFalse(output.getAll().contains("private-"));assertFalse(result.toString().contains("release"));assertThrows(UnsupportedOperationException.class,()->result.jobs().clear());
    }
    @Test void signedLogDownloadNeverReceivesInstallationTokenOrGithubHeaders(){
        var result=adapter(16000,Duration.ofSeconds(3),true).readJobLog(request,"701");
        assertEquals("private-raw-log α😀\nline2",new String(result.bytes(),StandardCharsets.UTF_8));assertTrue(download.headers().map().isEmpty());assertFalse(result.toString().contains("private-raw"));var bytes=result.bytes();bytes[0]=0;assertEquals('p',result.bytes()[0]);
    }
    @ParameterizedTest @ValueSource(strings={"wrong-app","missing-permissions","expired-token","expire-final-scope","changed-updated","changed-status","changed-sha","write","wide-token","wrong-owner","wrong-workflow","wrong-attempt","wrong-job-run","duplicate-job","replacement","rate","malformed","trailing","expire","suspended","lost-mint"})
    void rejectsCrossingsIncompatibleAndUncertainResultsWithoutRetryOrPrivateCause(String scenario,CapturedOutput output){
        mode=scenario;var failure=assertThrowsExactly(CaptureFailure.class,()->adapter().readRun(request));assertEquals(Set.of("wrong-app","wide-token","wrong-owner","wrong-workflow","wrong-attempt","replacement","suspended").contains(scenario)?SCOPE_CHANGED:UNAVAILABLE,failure.reason());assertNull(failure.getCause());assertFalse(failure.toString().contains("private"));assertTrue(mintCalls.get()<=1);assertFalse(output.getAll().contains("private-"));
    }
    @ParameterizedTest @ValueSource(strings={"changed-after-log","invalid-utf8","non-utf8-charset","encoded-log"})
    void refusesChangedSourceVersionsOrIncompatibleLogRepresentations(String scenario){mode=scenario;failure(UNAVAILABLE,()->adapter(16000,Duration.ofSeconds(3),true).readJobLog(request,"701"));}
    @Test void refusesUnapprovedDownloadHostWithoutSendingItsRequest(){mode="evil-download";assertThrowsExactly(CaptureFailure.class,()->adapter(16000,Duration.ofSeconds(3),true).readJobLog(request,"701"));assertNull(download);}
    @Test void scopeLossAndAmbientSqlPreventFurtherProtectedReads(){
        loseScopeAt=1;failure(SCOPE_CHANGED,()->adapter().readRun(request));assertEquals(0,calls.get());loseScopeAt=Integer.MAX_VALUE;
        TransactionSynchronizationManager.setActualTransactionActive(true);try{failure(INVALID_INPUT,()->adapter().readRun(request));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}assertEquals(0,calls.get());
        loseScopeAt=scopeCalls.get()+3;failure(SCOPE_CHANGED,()->adapter().readRun(request));assertEquals(1,calls.get());assertEquals(0,mintCalls.get());
    }
    @Test void enforcesAggregateBytesAndFullResponseDeadline(){failure(UNAVAILABLE,()->adapter(20,Duration.ofSeconds(3),false).readRun(request));mode="timeout";assertTimeoutPreemptively(Duration.ofSeconds(2),()->failure(UNAVAILABLE,()->adapter(16000,Duration.ofMillis(100),false).readRun(request)));}
    byte[] zip(Map<String,byte[]> entries) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)){for(var entry:entries.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
    GitHubMachineTransport archiveAdapter(int compressed,int expanded,int entries,int step){return adapter(16000,Duration.ofSeconds(3),true,new GitHubMachineTransport.ArchiveLimits(compressed,expanded,entries,step));}
    @Test void bindsPerStepArchiveToActualAttemptAndKeepsAggregatePrivate(CapturedOutput output) throws Exception {
        archive=zip(Map.of("0_job-1.txt","aggregate-never-step".getBytes(StandardCharsets.UTF_8),"job-1/1_step-1.txt","2026-11-10T11:01:03Z phase α".getBytes(StandardCharsets.UTF_8),"job-2/2_step-2.txt","different step 😀".getBytes(StandardCharsets.UTF_8)));
        var result=archiveAdapter(4096,4096,8,1024).readStepLogs(request);
        assertEquals(request,result.run().request());assertEquals(2,result.logs().size());var first=result.logs().stream().filter(l->l.jobId().equals("701")).findFirst().orElseThrow();assertEquals(1,first.stepNumber());assertEquals("2026-11-10T11:01:03Z phase α",new String(first.bytes(),StandardCharsets.UTF_8));
        var copy=first.bytes();copy[0]=0;assertEquals('2',first.bytes()[0]);assertThrows(UnsupportedOperationException.class,()->result.logs().clear());assertTrue(download.headers().map().isEmpty());assertFalse(output.getAll().contains("phase α"));assertFalse(first.toString().contains("phase"));assertEquals(3,runReads.get());
    }
    @ParameterizedTest @ValueSource(strings={"../escape.txt","job-1/../1_step-1.txt","job-1/2_step-1.txt","job-1/1_other-name.txt","unknown/1_step-1.txt","job-1\\1_step-1.txt"})
    void rejectsUnprovenArchivePathsWithoutReturningAnyEvidence(String path) throws Exception {archive=zip(Map.of(path,"private archive".getBytes(StandardCharsets.UTF_8)));failure(UNAVAILABLE,()->archiveAdapter(4096,4096,8,1024).readStepLogs(request));}
    @Test void refusesCompressionExpansionEntryAndStepBudgets() throws Exception {
        archive=zip(Map.of("job-1/1_step-1.txt",new byte[8192]));
        failure(UNAVAILABLE,()->archiveAdapter(archive.length-1,16384,8,8192).readStepLogs(request));failure(UNAVAILABLE,()->archiveAdapter(4096,4096,8,4096).readStepLogs(request));failure(UNAVAILABLE,()->archiveAdapter(4096,16384,8,1024).readStepLogs(request));
        archive=zip(Map.of("job-1/1_step-1.txt",new byte[1],"job-2/2_step-2.txt",new byte[1]));failure(UNAVAILABLE,()->archiveAdapter(4096,4096,1,1024).readStepLogs(request));
    }
    @Test void refusesInvalidUtf8TruncationAndDuplicateArchiveIdentity() throws Exception {
        archive=zip(Map.of("job-1/1_step-1.txt",new byte[]{(byte)0xc3,0x28}));failure(UNAVAILABLE,()->archiveAdapter(4096,4096,8,1024).readStepLogs(request));
        archive=zip(Map.of("job-1/1_step-1.txt","ok".getBytes(StandardCharsets.UTF_8)));archive=Arrays.copyOf(archive,archive.length-22);failure(UNAVAILABLE,()->archiveAdapter(4096,4096,8,1024).readStepLogs(request));
        archive=zip(Map.of("job-1/1_step-1.txt",new byte[1],"job-2/2_step-2.txt",new byte[1]));var from="job-2/2_step-2.txt".getBytes(StandardCharsets.UTF_8);var to="job-1/1_step-1.txt".getBytes(StandardCharsets.UTF_8);for(int i=0;i<=archive.length-from.length;i++){boolean match=true;for(int j=0;j<from.length;j++)if(archive[i+j]!=from[j]){match=false;break;}if(match)System.arraycopy(to,0,archive,i,to.length);}failure(UNAVAILABLE,()->archiveAdapter(4096,4096,8,1024).readStepLogs(request));
    }
    @Test void fencesArchiveSourceAndRefusesMissingArchiveConfiguration() throws Exception {
        failure(INVALID_STATE,()->adapter().readStepLogs(request));assertEquals(0,calls.get());archive=zip(Map.of("job-1/1_step-1.txt","ok".getBytes(StandardCharsets.UTF_8)));mode="changed-after-log";failure(UNAVAILABLE,()->archiveAdapter(4096,4096,8,1024).readStepLogs(request));
    }
    void failure(CaptureFailure.Reason reason,org.junit.jupiter.api.function.Executable action){assertEquals(reason,assertThrowsExactly(CaptureFailure.class,action).reason());}
}
