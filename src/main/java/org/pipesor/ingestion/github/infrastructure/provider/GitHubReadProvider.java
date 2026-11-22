package org.pipesor.ingestion.github.infrastructure.provider;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.*;

/** User-token read boundary; admission, restore safety and credential resolution remain external. */
public final class GitHubReadProvider implements ReadOnlyGitHub, org.pipesor.ingestion.auth.application.ports.GitHubLoginDirectory {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI root;
    private final Function<Identity, Credential> credentials;
    private final String appId, apiVersion;
    private final Duration budget;
    private final int pageSize, maxPages, maxItems, maxBytes;
    private final Clock clock;

    public record Credential(Identity identity, String token, Instant expiresAt) {
        public Credential { Objects.requireNonNull(identity); GitHubUserTokens.token(token); Objects.requireNonNull(expiresAt); }
        @Override public String toString() { return "GitHubCredential[private]"; }
    }
    /** The API root is validated by the caller's transport policy (configurable GitHub API base URL). */
    public GitHubReadProvider(HttpClient client, URI root, Function<Identity, Credential> credentials, String appId, String apiVersion,
            Duration budget, int pageSize, int maxPages, int maxItems, int maxBytes, Clock clock) {
        this.client=Objects.requireNonNull(client); this.root=Objects.requireNonNull(root); this.credentials=Objects.requireNonNull(credentials);
        this.appId=Objects.requireNonNull(appId); this.apiVersion=Objects.requireNonNull(apiVersion);
        this.budget=Objects.requireNonNull(budget); this.clock=Objects.requireNonNull(clock);
        if (!appId.matches("[1-9][0-9]*") || !apiVersion.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                || client.followRedirects()!=HttpClient.Redirect.NEVER || budget.isZero() || budget.isNegative()
                || pageSize<1 || pageSize>100 || maxPages<1 || maxItems<1 || maxBytes<1) {
            throw new IllegalArgumentException("Explicit GitHub provider configuration required");
        }
        this.pageSize=pageSize; this.maxPages=maxPages; this.maxItems=maxItems; this.maxBytes=maxBytes;
    }
    @Override public Observation<Identity,String> currentUser(Identity request) {
        return execute(request, s -> new Observation<>(request, id(s.get("/user"), "id"), clock.instant()), false);
    }
    @Override public Observation<Identity,List<RepositoryFacts>> repositories(Identity request) {
        return execute(request, s -> {
            var result=new ArrayList<RepositoryFacts>(); var seen=new HashSet<String>();
            for (var installation:s.installations()) {
                if (!installation.active()) { continue; }
                for (var repo:s.pages("/user/installations/"+installation.id()+"/repositories", "repositories")) {
                    var facts=facts(repo, installation);
                    if (!seen.add(facts.target().repositoryId()) || result.size()>=maxItems) { throw failure(UNAVAILABLE); }
                    result.add(facts);
                }
            }
            return new Observation<>(request,List.copyOf(result),clock.instant());
        }, true);
    }
    @Override public Observation<RepositoryRequest,RepositoryFacts> repository(RepositoryRequest request) {
        return execute(request.identity(),s -> new Observation<>(request,s.repository(request.repository()),clock.instant()),true);
    }
    @Override public Observation<RepositoryRequest,List<WorkflowMetadata>> workflows(RepositoryRequest request) {
        return execute(request.identity(),s -> {
            var facts=s.repository(request.repository());
            s.verifyRepositoryName(facts);
            var result=new ArrayList<WorkflowMetadata>(); var ids=new HashSet<String>();
            for(var item:s.pages("/repos/"+facts.fullName()+"/actions/workflows","workflows")) {
                var workflow=workflow(item,request.repository());
                if(!ids.add(workflow.workflowId())) { throw failure(UNAVAILABLE); }
                result.add(workflow);
            }
            s.verifyRepositoryName(facts);
            return new Observation<>(request,List.copyOf(result),clock.instant());
        },true);
    }
    @Override public Observation<WorkflowRequest,WorkflowMetadata> workflow(WorkflowRequest request) {
        return execute(request.repository().identity(),s -> {
            positive(request.workflowId());
            var facts=s.repository(request.repository().repository());
            s.verifyRepositoryName(facts);
            var result=workflow(s.get("/repos/"+facts.fullName()+"/actions/workflows/"+request.workflowId()),facts.target());
            if(!request.workflowId().equals(result.workflowId())) { throw failure(UNAVAILABLE); }
            s.verifyRepositoryName(facts);
            return new Observation<>(request,result,clock.instant());
        },true);
    }
    @Override public Facts read(String userAccessToken, Instant accessExpiresAt) {
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()) { throw failure(UNAVAILABLE); }
            GitHubUserTokens.token(userAccessToken); Objects.requireNonNull(accessExpiresAt);
            var session=new Session(userAccessToken,accessExpiresAt,System.nanoTime());
            session.check();
            var user=session.get("/user");
            var userId=id(user,"id"); var login=text(user,"login");
            if(!login.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) { throw failure(UNAVAILABLE); }
            var repositories=new ArrayList<RepositoryFacts>(); var seen=new HashSet<String>();
            for(var installation:session.installations()) {
                for(var repo:session.pages("/user/installations/"+installation.id()+"/repositories","repositories")) {
                    var facts=facts(repo,installation);
                    if(!seen.add(facts.target().repositoryId()) || repositories.size()>=maxItems) { throw failure(UNAVAILABLE); }
                    repositories.add(facts);
                }
            }
            if(!userId.equals(id(session.get("/user"),"id"))) { throw failure(UNAVAILABLE); }
            session.check();
            return new Facts(userId,login,List.copyOf(repositories));
        } catch(GitHubAccessFailure fixed) { throw fixed; }
        catch(RuntimeException privateFailure) { throw failure(UNAVAILABLE); }
    }
    @Override public Observation<RunsRequest,RunPage> runs(RunsRequest request) {
        return execute(request.workflow().repository().identity(),s -> {
            positive(request.workflow().workflowId());
            if(!java.util.Set.of("failure","success").contains(request.conclusion()) || request.page()<1 || request.pageSize()<1 || request.pageSize()>100 || request.page()>maxPages) { throw failure(UNAVAILABLE); }
            var facts=s.repository(request.workflow().repository().repository());
            s.verifyRepositoryName(facts);
            var body=s.get("/repos/"+facts.fullName()+"/actions/workflows/"+request.workflow().workflowId()
                    +"/runs?status="+request.conclusion()+"&per_page="+request.pageSize()+"&page="+request.page());
            var count=body.get("total_count"); var array=body.get("workflow_runs");
            if(count==null || !count.isIntegralNumber() || !count.canConvertToLong() || count.longValue()<0
                    || array==null || !array.isArray() || array.size()>request.pageSize()) { throw failure(UNAVAILABLE); }
            var runs=new ArrayList<WorkflowRun>();
            for(var item:array) {
                if(!item.isObject() || !request.workflow().workflowId().equals(id(item,"workflow_id"))) { throw failure(UNAVAILABLE); }
                var attempt=item.get("run_attempt");
                if(attempt==null || !attempt.isIntegralNumber() || !attempt.canConvertToInt() || attempt.intValue()<1) { throw failure(UNAVAILABLE); }
                var conclusion=item.get("conclusion");
                // GitHub filters by status; a run with another conclusion is a provider inconsistency.
                if(conclusion==null || !request.conclusion().equals(conclusion.asString())) { throw failure(UNAVAILABLE); }
                var branch=item.get("head_branch");
                runs.add(new WorkflowRun(id(item,"id"),attempt.intValue(),conclusion==null || conclusion.isNull()?null:conclusion.asString(),
                        branch==null || branch.isNull()?null:branch.asString(),text(item,"head_sha"),Instant.parse(text(item,"created_at")),
                        URI.create(text(item,"html_url"))));
            }
            s.verifyRepositoryName(facts);
            boolean more=(long)request.page()*request.pageSize()<count.longValue() && !array.isEmpty();
            return new Observation<>(request,new RunPage(List.copyOf(runs),more),clock.instant());
        },true);
    }
    private <T>T execute(Identity identity,Function<Session,T> work,boolean verifyUser) {
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive() || identity==null || identity.link().generationId()==null
                    || !identity.context().tenantId().equals(identity.link().tenantId())
                    || !identity.context().subjectRef().equals(identity.link().subjectRef())) { throw failure(UNAVAILABLE); }
            var started=System.nanoTime();
            var credential=credentials.apply(identity);
            if(credential==null || !identity.equals(credential.identity())) { throw failure(UNAVAILABLE); }
            var session=new Session(credential.token(),credential.expiresAt(),started);
            session.check();
            if(verifyUser && !identity.link().githubUserId().equals(id(session.get("/user"),"id"))) { throw failure(CONNECTION_REQUIRED); }
            var result=work.apply(session); session.check(); return result;
        } catch(GitHubAccessFailure fixed) { throw fixed; }
        catch(RuntimeException privateFailure) { throw failure(UNAVAILABLE); }
    }
    private record Installation(String id,String account,boolean active) {}
    private final class Session {
        final String token; final Instant expiresAt; final long started; int pages; int remainingBytes=maxBytes;
        Session(String token,Instant expiresAt,long started) { this.token=token; this.expiresAt=expiresAt; this.started=started; }
        long remaining() {
            if(!expiresAt.isAfter(clock.instant())) { throw failure(CONNECTION_REQUIRED); }
            long remaining=Math.min(budget.toNanos()-(System.nanoTime()-started), Duration.between(clock.instant(),expiresAt).toNanos());
            if(remaining<=0) { throw failure(UNAVAILABLE); } return remaining;
        }
        void check() { remaining(); }
        List<Installation> installations() {
            var result=new ArrayList<Installation>(); var seen=new HashSet<String>();
            for(var item:pages("/user/installations","installations")) {
                var installId=id(item,"id");
                if(!seen.add(installId) || !appId.equals(id(item,"app_id"))) { throw failure(UNAVAILABLE); }
                var account=id(object(item,"account"),"id");
                if(!item.has("suspended_at")) { throw failure(UNAVAILABLE); }
                var suspended=item.get("suspended_at");
                if(!suspended.isNull() && !suspended.isString()) { throw failure(UNAVAILABLE); }
                result.add(new Installation(installId,account,suspended.isNull()));
            }
            return result;
        }
        void verifyRepositoryName(RepositoryFacts facts) {
            var repo=get("/repos/"+facts.fullName());
            if(!facts.target().repositoryId().equals(id(repo,"id")) || !facts.accountId().equals(id(object(repo,"owner"),"id"))) { throw failure(UNAVAILABLE); }
        }
        RepositoryFacts repository(RepositoryTarget target) {
            positive(target.installationId()); positive(target.repositoryId());
            var installation=installations().stream().filter(i->i.id().equals(target.installationId())).findFirst().orElseThrow(()->failure(DENIED));
            if(!installation.active()) { throw failure(DENIED); }
            RepositoryFacts found=null;
            var seen=new HashSet<String>();
            for(var repo:pages("/user/installations/"+installation.id()+"/repositories","repositories")) {
                var value=facts(repo,installation);
                if(!seen.add(value.target().repositoryId())) { throw failure(UNAVAILABLE); }
                if(value.target().equals(target)) { found=value; }
            }
            if(found==null || !found.readable()) { throw failure(DENIED); }
            return found;
        }
        List<JsonNode> pages(String path,String field) {
            var result=new ArrayList<JsonNode>(); int page=1; Long total=null;
            while(true) {
                if(++pages>maxPages) { throw failure(UNAVAILABLE); }
                var response=request(path+"?per_page="+pageSize+"&page="+page);
                var body=parse(response.body());
                var count=body.get("total_count"); var array=body.get(field);
                if(count==null || !count.isIntegralNumber() || !count.canConvertToLong() || count.longValue()<0
                        || count.longValue()>maxItems || array==null || !array.isArray() || array.size()>pageSize) { throw failure(UNAVAILABLE); }
                if(total!=null && total.longValue()!=count.longValue()) { throw failure(UNAVAILABLE); }
                total=count.longValue();
                for(var item:array) { if(!item.isObject()) { throw failure(UNAVAILABLE); } result.add(item); }
                if(result.size()>maxItems || result.size()>total) { throw failure(UNAVAILABLE); }
                var next=next(response,path,page+1);
                if(!next) { if(result.size()!=total) { throw failure(UNAVAILABLE); } return result; }
                if(array.isEmpty() || result.size()>=total) { throw failure(UNAVAILABLE); }
                page++;
            }
        }
        JsonNode get(String path) { return parse(request(path).body()); }
        HttpResponse<byte[]> request(String path) {
            CompletableFuture<HttpResponse<byte[]>> pending=null;
            try {
                var nanos=remaining();
                if(remainingBytes<=0) { throw failure(UNAVAILABLE); }
                var request=HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofNanos(nanos))
                        .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version",apiVersion)
                        .header("Authorization","Bearer "+token).GET().build();
                pending=client.sendAsync(request,info->new BoundedBody(remainingBytes));
                var response=pending.get(nanos,TimeUnit.NANOSECONDS); remainingBytes-=response.body().length; check();
                if(response.statusCode()==401) { throw failure(CONNECTION_REQUIRED); }
                if(response.headers().firstValue("X-GitHub-SSO").isPresent()) { throw failure(UNAVAILABLE); }
                if(response.statusCode()==429 || (response.statusCode()!=200 && (response.headers().firstValue("Retry-After").isPresent()
                        || response.headers().firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent()))) { throw failure(UNAVAILABLE); }
                if(response.statusCode()==403) { throw failure(UNAVAILABLE); }
                if(response.statusCode()==404) { throw failure(DENIED); }
                if(response.statusCode()!=200 || !Set.of("application/json","application/vnd.github+json").contains(
                        response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().toLowerCase(Locale.ROOT))) { throw failure(UNAVAILABLE); }
                return response;
            } catch(GitHubAccessFailure fixed) { throw fixed; }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw failure(UNAVAILABLE); }
            catch(Exception privateFailure) { throw failure(UNAVAILABLE); }
            finally { if(pending!=null && !pending.isDone()) { pending.cancel(true); } }
        }
    }
    private boolean next(HttpResponse<?> response,String path,int page) {
        String found=null;
        for(var header:response.headers().allValues("Link")) for(var entry:header.split(",")) {
            var matcher=java.util.regex.Pattern.compile("\\s*<([^>]+)>;\\s*rel=\"(next|prev|first|last)\"\\s*").matcher(entry);
            if(!matcher.matches()) { throw failure(UNAVAILABLE); }
            if(matcher.group(2).equals("next")) { if(found!=null) { throw failure(UNAVAILABLE); } found=matcher.group(1); }
        }
        if(found==null) { return false; }
        var uri=root.resolve(found); var expected=root.resolve(path);
        if(!Objects.equals(uri.getScheme(),expected.getScheme()) || !Objects.equals(uri.getRawAuthority(),expected.getRawAuthority())
                || !uri.getRawPath().equals(path) || uri.getFragment()!=null) { throw failure(UNAVAILABLE); }
        var query=new HashMap<String,String>();
        if(uri.getRawQuery()==null) { throw failure(UNAVAILABLE); }
        for(var pair:uri.getRawQuery().split("&")) {
            var parts=pair.split("=",-1); if(parts.length!=2 || query.put(parts[0],parts[1])!=null) { throw failure(UNAVAILABLE); }
        }
        if(!query.equals(Map.of("per_page",Integer.toString(pageSize),"page",Integer.toString(page)))) { throw failure(UNAVAILABLE); }
        return true;
    }
    private static RepositoryFacts facts(JsonNode repo,Installation installation) {
        var repoId=id(repo,"id");
        if(!installation.account().equals(id(object(repo,"owner"),"id"))) { throw failure(UNAVAILABLE); }
        var name=text(repo,"full_name");
        if(!name.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+") || name.endsWith("/.") || name.endsWith("/..")) { throw failure(UNAVAILABLE); }
        var permissions=object(repo,"permissions"); var pull=bool(permissions,"pull"); var admin=bool(permissions,"admin");
        if(!repo.has("default_branch")) { throw failure(UNAVAILABLE); }
        var branch=repo.get("default_branch");
        if(!branch.isNull() && !branch.isString()) { throw failure(UNAVAILABLE); }
        return new RepositoryFacts(new RepositoryTarget(repoId,installation.id()),installation.account(),installation.active(),pull,true,true,admin,name,branch.isNull()?null:branch.asString());
    }
    private static WorkflowMetadata workflow(JsonNode body,RepositoryTarget target) {
        return new WorkflowMetadata(target,id(body,"id"),text(body,"name"),text(body,"path"));
    }
    private static JsonNode parse(byte[] bytes) { var body=JSON.readTree(bytes); if(body==null || !body.isObject()) { throw failure(UNAVAILABLE); } return body; }
    private static JsonNode object(JsonNode body,String name) { var value=body.get(name); if(value==null || !value.isObject()) { throw failure(UNAVAILABLE); } return value; }
    private static String text(JsonNode body,String name) { var value=body.get(name); if(value==null || !value.isString() || value.asString().isBlank()) { throw failure(UNAVAILABLE); } return value.asString(); }
    private static String id(JsonNode body,String name) {
        var value=body.get(name); if(value==null || (!value.isString() && !value.isIntegralNumber())) { throw failure(UNAVAILABLE); }
        var id=value.isString()?value.asString():value.asText(); positive(id); return id;
    }
    private static boolean bool(JsonNode body,String name) { var value=body.get(name); if(value==null || !value.isBoolean()) { throw failure(UNAVAILABLE); } return value.booleanValue(); }
    private static void positive(String id) { if(id==null || !id.matches("[1-9][0-9]*")) { throw failure(UNAVAILABLE); } }
    private static GitHubAccessFailure failure(GitHubAccessFailure.Reason reason) { return new GitHubAccessFailure(reason); }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> body=new CompletableFuture<>(); final ByteArrayOutputStream bytes=new ByteArrayOutputStream(); final int limit; Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit=limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) { if(buffer.remaining()>limit-bytes.size()) { subscription.cancel();body.completeExceptionally(failure(UNAVAILABLE));return; } while(buffer.hasRemaining()) { bytes.write(buffer.get()); } }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { body.completeExceptionally(failure(UNAVAILABLE)); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
