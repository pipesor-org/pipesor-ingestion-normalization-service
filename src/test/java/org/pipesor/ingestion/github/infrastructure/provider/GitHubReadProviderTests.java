package org.pipesor.ingestion.github.infrastructure.provider;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub.*;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.springframework.boot.test.system.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.*;

@ExtendWith(OutputCaptureExtension.class)
class GitHubReadProviderTests {
    private static final Instant NOW=Instant.parse("2026-11-09T12:00:00Z");
    private static final Identity IDENTITY=new Identity(new AccessContext("tenant-4","actor-8","actor-8",AccessOperation.VIEW,"corr-7"),
            new GitHubLink("tenant-4","actor-8","9207199254740999","rev-8",NOW,UUID.fromString("11111111-1111-4111-8111-111111111111")));
    private static final RepositoryTarget TARGET=new RepositoryTarget("900719925474099312345","81");
    private static final String REPO="{\"id\":900719925474099312345,\"owner\":{\"id\":54},\"full_name\":\"owner/repository\",\"default_branch\":null,\"permissions\":{\"pull\":true,\"admin\":false}}";
    private static final String INSTALL="{\"id\":81,\"app_id\":24,\"account\":{\"id\":54},\"suspended_at\":null}";
    private HttpServer server; private ExecutorService executor; private URI root;
    private final Map<String,String> responses=new ConcurrentHashMap<>(); private final List<String> calls=new CopyOnWriteArrayList<>();
    private volatile String failurePath, extraHeader, extraValue; private volatile int status=200; private volatile long delay; private volatile boolean replaceAfterWorkflow, expireAfterResponse;
    private volatile Identity bound=IDENTITY; private volatile Instant expiry=NOW.plusSeconds(300);
    private volatile Instant time=NOW; private final AtomicInteger credentials=new AtomicInteger();
    private final Clock clock=new Clock() { public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return time;} };
    @BeforeEach void start() throws Exception {
        responses.put("/user","{\"id\":9207199254740999}");
        responses.put("/user/installations?per_page=2&page=1",page("installations",INSTALL));
        responses.put("/user/installations/81/repositories?per_page=2&page=1",page("repositories",REPO));
        responses.put("/repos/owner/repository",REPO);
        responses.put("/repos/owner/repository/actions/workflows?per_page=2&page=1",page("workflows",workflow("71992547409933222","CI alpha",".github/workflows/alpha.yaml")));
        responses.put("/repos/owner/repository/actions/workflows/71992547409933222",workflow("71992547409933222","CI beta",".github/workflows/beta.yml"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); executor=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/",exchange->{
            var path=exchange.getRequestURI().toString(); calls.add(path);
            assertEquals("GET",exchange.getRequestMethod()); assertEquals("Bearer private-user-token",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("2026-03-10",exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"));
            if(replaceAfterWorkflow && path.contains("/actions/workflows")) responses.put("/repos/owner/repository",REPO.replace("900719925474099312345","789"));
            if(expireAfterResponse) time=expiry;
            var failed=path.equals(failurePath); var text=responses.getOrDefault(path,"{\"private-error\":\"private-token\"}");
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(failed && extraHeader!=null) exchange.getResponseHeaders().set(extraHeader,extraValue);
            var bytes=text.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(failed?status:200,bytes.length);
            try { if(failed && delay>0) Thread.sleep(delay); exchange.getResponseBody().write(bytes); }
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();} catch(java.io.IOException cancelled){} finally{exchange.close();}
        });server.start();root=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop(){server.stop(0);executor.close();}
    private static String page(String field,String item){return "{\"total_count\":1,\""+field+"\":["+item+"]}";}
    private static String workflow(String id,String name,String path){return "{\"id\":"+id+",\"name\":\""+name+"\",\"path\":\""+path+"\"}";}
    private GitHubReadProvider provider(){return provider(20,100,32768,Duration.ofSeconds(3));}
    private GitHubReadProvider provider(int pages,int items,int bytes,Duration budget){
        return new GitHubReadProvider(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),root,identity->{credentials.incrementAndGet();return new GitHubReadProvider.Credential(bound,"private-user-token",expiry);},"24","2026-03-10",budget,2,pages,items,bytes,clock);
    }
    private RepositoryRequest request(){return new RepositoryRequest(IDENTITY,TARGET);}
    private void fail(GitHubAccessFailure.Reason reason,org.junit.jupiter.api.function.Executable action){var f=assertThrowsExactly(GitHubAccessFailure.class,action);assertEquals(reason,f.reason());assertNull(f.getCause());assertFalse(f.toString().contains("private-"));}
    @Test void realUserAndIntersectionFactsPreserveIdsNullBranchAndFalseAdministration(CapturedOutput output){
        var provider=provider();assertEquals(IDENTITY.link().githubUserId(),provider.currentUser(IDENTITY).value());
        var observation=provider.repository(request());var facts=observation.value();assertEquals(request(),observation.request());assertEquals(NOW,observation.observedAt());
        assertEquals(TARGET,facts.target());assertEquals("54",facts.accountId());assertTrue(facts.readable());assertFalse(facts.administrator());assertNull(facts.defaultBranch());
        assertEquals(2,credentials.get());assertFalse(output.getAll().contains("private-user-token"));
    }
    @Test void completeDiscoveryAndWorkflowReadsUseProviderNamesAndRevalidateRepositoryIds(){
        assertEquals(TARGET,provider().repositories(IDENTITY).value().getFirst().target());
        var list=provider().workflows(request()).value();assertEquals("CI alpha",list.getFirst().name());assertEquals(".github/workflows/alpha.yaml",list.getFirst().file());
        var selected=provider().workflow(new WorkflowRequest(request(),"71992547409933222")).value();assertEquals("CI beta",selected.name());assertEquals(".github/workflows/beta.yml",selected.file());
        assertEquals(4,calls.stream().filter("/repos/owner/repository"::equals).count());
    }
    @Test void loginDirectoryReadsTheTokenUserAndEveryInstallationRepository(CapturedOutput output){
        responses.put("/user","{\"id\":9207199254740999,\"login\":\"octo-cat\"}");
        var facts=provider().read("private-user-token",expiry);
        assertEquals("9207199254740999",facts.githubUserId());assertEquals("octo-cat",facts.login());
        assertEquals(TARGET,facts.repositories().getFirst().target());assertEquals("54",facts.repositories().getFirst().accountId());
        assertEquals(0,credentials.get());assertFalse(output.getAll().contains("private-user-token"));
        responses.put("/user","{\"id\":9207199254740999,\"login\":\"../bad\"}");
        fail(UNAVAILABLE,()->provider().read("private-user-token",expiry));
        fail(CONNECTION_REQUIRED,()->provider().read("private-user-token",NOW));
    }
    @Test void failedRunsAreOneBoundedPageOfTheSelectedWorkflow(){
        var run="{\"id\":7001,\"workflow_id\":71992547409933222,\"run_attempt\":2,\"conclusion\":\"failure\",\"head_branch\":\"main\",\"head_sha\":\"abc\",\"created_at\":\"2026-11-08T10:00:00Z\",\"run_started_at\":\"2026-11-08T12:00:00Z\",\"html_url\":\"https://github.com/owner/repository/actions/runs/7001\"}";
        var path="/repos/owner/repository/actions/workflows/71992547409933222/runs?status=failure&per_page=1&page=1";
        responses.put(path,"{\"total_count\":3,\"workflow_runs\":["+run+"]}");
        var runs=new RunsRequest(new WorkflowRequest(request(),"71992547409933222"),"failure",1,1);
        var page=provider().runs(runs).value();
        assertEquals("7001",page.runs().getFirst().runId());assertEquals(2,page.runs().getFirst().attempt());assertTrue(page.more());
        // A re-run keeps created_at from the first attempt; run_started_at is this attempt's start.
        assertEquals(java.time.Instant.parse("2026-11-08T12:00:00Z"),page.runs().getFirst().runStartedAt());
        responses.put(path,"{\"total_count\":3,\"workflow_runs\":["+run.replace("71992547409933222","1")+"]}");
        fail(UNAVAILABLE,()->provider().runs(runs));
        responses.put(path,"{\"total_count\":3,\"workflow_runs\":["+run+","+run+"]}");
        fail(UNAVAILABLE,()->provider().runs(runs));
        // A run whose conclusion does not match the requested filter is a provider inconsistency.
        responses.put(path,"{\"total_count\":1,\"workflow_runs\":["+run.replace("\"failure\"","\"success\"")+"]}");
        fail(UNAVAILABLE,()->provider().runs(runs));
    }
    @Test void successfulRunsAreListedForVerification(){
        var run="{\"id\":7002,\"workflow_id\":71992547409933222,\"run_attempt\":1,\"conclusion\":\"success\",\"head_branch\":\"main\",\"head_sha\":\"def\",\"created_at\":\"2026-11-08T11:00:00Z\",\"html_url\":\"https://github.com/owner/repository/actions/runs/7002\"}";
        responses.put("/repos/owner/repository/actions/workflows/71992547409933222/runs?status=success&per_page=1&page=1","{\"total_count\":1,\"workflow_runs\":["+run+"]}");
        var page=provider().runs(new RunsRequest(new WorkflowRequest(request(),"71992547409933222"),"success",1,1)).value();
        assertEquals("7002",page.runs().getFirst().runId());assertEquals("success",page.runs().getFirst().conclusion());
        assertEquals(null,page.runs().getFirst().runStartedAt(),"a run without run_started_at keeps it absent");
    }
    @Test void preservesTrueAdministrationAndNullableBranchWithoutDefaultBranchFilter(){
        responses.put("/user/installations/81/repositories?per_page=2&page=1",page("repositories",REPO.replace("\"admin\":false","\"admin\":true").replace("\"default_branch\":null","\"default_branch\":\"release\"")));
        var facts=provider().repository(request()).value();assertTrue(facts.administrator());assertEquals("release",facts.defaultBranch());
    }
    @ParameterizedTest @ValueSource(strings={"wrong-app","wrong-account","missing-admin","missing-pull","missing-suspension","missing-branch","duplicate-repo","duplicate-install","trailing-json","duplicate-json","count-mismatch","wrong-repository-name-id","workflow-mismatch","unsafe-name"})
    void rejectsIncompatibleEvidenceWithoutPartialSuccess(String scenario){
        String installs="/user/installations?per_page=2&page=1", repos="/user/installations/81/repositories?per_page=2&page=1";
        switch(scenario){
            case "wrong-app"->responses.put(installs,page("installations",INSTALL.replace("24","25")));
            case "wrong-account"->responses.put(repos,page("repositories",REPO.replace("\"id\":54","\"id\":55")));
            case "missing-admin"->responses.put(repos,page("repositories",REPO.replace(",\"admin\":false","")));
            case "missing-pull"->responses.put(repos,page("repositories",REPO.replace("\"pull\":true,","")));
            case "missing-suspension"->responses.put(installs,page("installations",INSTALL.replace(",\"suspended_at\":null","")));
            case "missing-branch"->responses.put(repos,page("repositories",REPO.replace("\"default_branch\":null,","")));
            case "duplicate-repo"->responses.put(repos,"{\"total_count\":2,\"repositories\":["+REPO+","+REPO+"]}");
            case "duplicate-install"->responses.put(installs,"{\"total_count\":2,\"installations\":["+INSTALL+","+INSTALL+"]}");
            case "trailing-json"->responses.put(repos,responses.get(repos)+" {}");
            case "duplicate-json"->responses.put(repos,responses.get(repos).replace("\"total_count\":1","\"total_count\":1,\"total_count\":1"));
            case "count-mismatch"->responses.put(repos,responses.get(repos).replace("\"total_count\":1","\"total_count\":2"));
            case "wrong-repository-name-id"->responses.put("/repos/owner/repository",REPO.replace("900719925474099312345","789"));
            case "workflow-mismatch"->responses.put("/repos/owner/repository/actions/workflows/71992547409933222",workflow("42","other","file"));
            case "unsafe-name"->responses.put(repos,page("repositories",REPO.replace("owner/repository","../repository")));
        }
        fail(UNAVAILABLE,()->provider().workflow(new WorkflowRequest(request(),"71992547409933222")));
        if(scenario.equals("unsafe-name"))assertTrue(calls.stream().noneMatch(path->path.startsWith("/repos/")));
    }
    @ParameterizedTest @ValueSource(strings={"suspended","unreadable","missing-repo","missing-install"})
    void definitiveTargetLossIsDeniedWithoutPublicRepositoryFallback(String scenario){
        if(scenario.equals("suspended"))responses.put("/user/installations?per_page=2&page=1",page("installations",INSTALL.replace("null","\"2026-01-01T00:00:00Z\"")));
        if(scenario.equals("unreadable"))responses.put("/user/installations/81/repositories?per_page=2&page=1",page("repositories",REPO.replace("\"pull\":true","\"pull\":false")));
        if(scenario.equals("missing-repo"))responses.put("/user/installations/81/repositories?per_page=2&page=1","{\"total_count\":0,\"repositories\":[]}");
        if(scenario.equals("missing-install"))responses.put("/user/installations?per_page=2&page=1","{\"total_count\":0,\"installations\":[]}");
        fail(DENIED,()->provider().repository(request()));assertFalse(calls.contains("/repos/owner/repository"));
    }
    @Test void wrongSubjectGenerationLegacyAndExpiredCredentialFailBeforeNetwork(){
        bound=new Identity(new AccessContext("tenant-4","other","other",AccessOperation.VIEW,"corr-7"),IDENTITY.link());
        fail(UNAVAILABLE,()->provider().repository(request()));assertTrue(calls.isEmpty());
        bound=new Identity(IDENTITY.context(),new GitHubLink("tenant-4","actor-8","9207199254740999","rev-8",NOW,UUID.randomUUID()));
        fail(UNAVAILABLE,()->provider().repository(request()));assertTrue(calls.isEmpty());bound=IDENTITY;
        var legacy=new Identity(IDENTITY.context(),new GitHubLink("tenant-4","actor-8","9207199254740999","rev-8",NOW));
        fail(UNAVAILABLE,()->provider().currentUser(legacy));assertTrue(calls.isEmpty());
        expiry=NOW;fail(CONNECTION_REQUIRED,()->provider().currentUser(IDENTITY));assertTrue(calls.isEmpty());
    }
    @Test void actualUserMismatchRejectsBeforeReadingInstallations(){responses.put("/user","{\"id\":55}");fail(CONNECTION_REQUIRED,()->provider().repository(request()));assertEquals(List.of("/user"),calls);}
    @ParameterizedTest @ValueSource(strings={"unauthorized","forbidden","not-found","outage","rate","retry-after","redirect"})
    void statusAndSecurityHeadersNeverProduceEmptySuccessOrLeakProviderErrors(String scenario){
        failurePath="/user/installations?per_page=2&page=1";responses.put(failurePath,"{\"message\":\"private-provider-body\"}");
        status=switch(scenario){case "unauthorized"->401;case "forbidden","rate","retry-after","sso"->403;case "not-found"->404;case "redirect"->302;default->503;};
        switch(scenario){case "rate"->{extraHeader="X-RateLimit-Remaining";extraValue="0";}case "retry-after"->{extraHeader="Retry-After";extraValue="5";}case "sso"->{extraHeader="X-GitHub-SSO";extraValue="partial-results; organizations=54";}case "redirect"->{extraHeader="Location";extraValue=root+"/private-target";}}
        var reason=scenario.equals("unauthorized")?CONNECTION_REQUIRED:scenario.equals("not-found")?DENIED:UNAVAILABLE;
        fail(reason,()->provider().repositories(IDENTITY));assertEquals(2,calls.size());
    }
    @ParameterizedTest @ValueSource(strings={"X-RateLimit-Remaining","Retry-After"})
    void successfulLastRateLimitSlotIsNotAnOutage(String header){
        failurePath="/user";extraHeader=header;extraValue="0";
        assertEquals(IDENTITY.link().githubUserId(),provider().currentUser(IDENTITY).value());
        assertEquals(List.of("/user"),calls);
    }
    @Test void partialSsoResponseCannotPublishAValidLookingCatalog(){
        failurePath="/user/installations?per_page=2&page=1";
        extraHeader="X-GitHub-SSO";extraValue="partial-results; organizations=54";
        fail(UNAVAILABLE,()->provider().repositories(IDENTITY));
        assertEquals(2,calls.size());
    }
    @Test void rejectsPaginationExfiltrationAndSupportsBoundedCompletePages(){
        failurePath="/user/installations/81/repositories?per_page=2&page=1";
        var second=REPO.replace("900719925474099312345","900719925474099312346");
        responses.put(failurePath,"{\"total_count\":3,\"repositories\":["+REPO+","+second+"]}");
        var third=REPO.replace("900719925474099312345","900719925474099312347");responses.put("/user/installations/81/repositories?per_page=2&page=2",page("repositories",third).replace("\"total_count\":1","\"total_count\":3"));
        extraHeader="Link";extraValue="<https://hostile.example/steal?per_page=2&page=2>; rel=\"next\"";
        fail(UNAVAILABLE,()->provider().repositories(IDENTITY));assertFalse(calls.stream().anyMatch(p->p.contains("steal")));
        extraValue="<"+root+"/user/installations/81/repositories?per_page=2&page=2>; rel=\"next\"";
        assertEquals(3,provider().repositories(IDENTITY).value().size());
        fail(UNAVAILABLE,()->provider(2,100,32768,Duration.ofSeconds(3)).repositories(IDENTITY));
    }
    @Test void rejectsNameReplacementAfterWorkflowReadAndExpiryDuringIo(){
        replaceAfterWorkflow=true;
        fail(UNAVAILABLE,()->provider().workflows(request()));
        replaceAfterWorkflow=false; expireAfterResponse=true;
        fail(CONNECTION_REQUIRED,()->provider().currentUser(IDENTITY));
    }
    @Test void aggregateByteBudgetRejectsMultipleIndividuallySmallResponses(){
        var sizes=List.of("/user","/user/installations?per_page=2&page=1","/user/installations/81/repositories?per_page=2&page=1")
                .stream().mapToInt(path->responses.get(path).getBytes(StandardCharsets.UTF_8).length).toArray();
        int limit=Arrays.stream(sizes).max().orElseThrow();
        assertTrue(Arrays.stream(sizes).sum()>limit);
        fail(UNAVAILABLE,()->provider(20,100,limit,Duration.ofSeconds(3)).repository(request()));
    }
    @Test void boundsBytesBodyTimeAndSqlBoundary(){
        responses.put("/user","{\"id\":9207199254740999,\"private\":\""+"a".repeat(1000)+"\"}");fail(UNAVAILABLE,()->provider(20,100,128,Duration.ofSeconds(3)).currentUser(IDENTITY));
        failurePath="/user";delay=700;fail(UNAVAILABLE,()->provider(20,100,32768,Duration.ofMillis(150)).currentUser(IDENTITY));
        int before=calls.size();TransactionSynchronizationManager.setActualTransactionActive(true);
        try{fail(UNAVAILABLE,()->provider().currentUser(IDENTITY));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertEquals(before,calls.size());
    }
}
