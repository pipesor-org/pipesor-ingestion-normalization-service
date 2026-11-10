package org.pipesor.ingestion.capture.infrastructure.github;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.pipesor.ingestion.capture.application.ports.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** No cache, automatic retries, runtime composition, workflow mutation or human credentials. */
public final class GitHubMachineTransport implements GitHubMachineCapture {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI origin;
    private final CurrentMachineScope authority;
    private final Supplier<AppJwt> jwt;
    private final String appId,version;
    private final Duration budget;
    private final int pageSize,maxPages,maxJobs,maxBytes,maxLogBytes;
    private final Set<String> downloadHosts;
    private final Clock clock;
    public record AppJwt(String appId,String token,Instant expiresAt) {
        public AppJwt { CaptureScope.external(appId);org.pipesor.ingestion.github.application.ports.GitHubUserTokens.token(token);Objects.requireNonNull(expiresAt); }
        @Override public String toString(){return "AppJwt[private]";}
    }
    public GitHubMachineTransport(CurrentMachineScope authority,Supplier<AppJwt> jwt,String appId,String version,Duration budget,
            int pageSize,int maxPages,int maxJobs,int maxBytes,int maxLogBytes,Set<String> downloadHosts,Clock clock) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(budget).build(),URI.create("https://api.github.com"),authority,jwt,appId,version,budget,pageSize,maxPages,maxJobs,maxBytes,maxLogBytes,downloadHosts,clock);
    }
    GitHubMachineTransport(HttpClient client,URI origin,CurrentMachineScope authority,Supplier<AppJwt> jwt,String appId,String version,Duration budget,
            int pageSize,int maxPages,int maxJobs,int maxBytes,int maxLogBytes,Set<String> downloadHosts,Clock clock) {
        this.client=Objects.requireNonNull(client);this.origin=Objects.requireNonNull(origin);this.authority=Objects.requireNonNull(authority);this.jwt=Objects.requireNonNull(jwt);
        this.appId=CaptureScope.external(appId);this.version=Objects.requireNonNull(version);this.budget=Objects.requireNonNull(budget);this.clock=Objects.requireNonNull(clock);
        if(client.followRedirects()!=HttpClient.Redirect.NEVER || !version.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || budget.isZero() || budget.isNegative() || pageSize<1 || pageSize>100 || maxPages<1 || maxJobs<1 || maxBytes<1 || maxLogBytes<1)throw new IllegalArgumentException("Explicit machine transport configuration required");
        budget.toNanos();this.pageSize=pageSize;this.maxPages=maxPages;this.maxJobs=maxJobs;this.maxBytes=maxBytes;this.maxLogBytes=maxLogBytes;
        this.downloadHosts=downloadHosts.stream().map(h->h.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(this.downloadHosts.stream().anyMatch(h->!h.matches("[A-Za-z0-9][A-Za-z0-9.-]*") || h.contains("*") || h.endsWith(".")))throw new IllegalArgumentException("Exact download hosts required");
    }
    @Override public Run readRun(Request request){return execute(request,null).run();}
    @Override public Log readJobLog(Request request,String jobId){CaptureScope.external(jobId);return execute(request,jobId);}
    private Log execute(Request request,String selectedJob) {
        try {
            if(request==null || TransactionSynchronizationManager.isActualTransactionActive() || !appId.equals(request.scope().appId()))throw new CaptureFailure(INVALID_INPUT);
            var s=new Session(request);s.authorized();var signing=Objects.requireNonNull(jwt.get());
            if(!appId.equals(signing.appId()))throw new CaptureFailure(SCOPE_CHANGED);
            s.expiry=signing.expiresAt();
            if(!appId.equals(id(s.json("/app",signing.token(),null,200),"id")))throw new CaptureFailure(SCOPE_CHANGED);
            var install=s.json("/app/installations/"+request.scope().installationId(),signing.token(),null,200);
            if(!request.scope().installationId().equals(id(install,"id")) || !appId.equals(id(install,"app_id")) || !request.scope().accountId().equals(id(object(install,"account"),"id")) || !install.has("suspended_at") || !install.get("suspended_at").isNull())throw new CaptureFailure(SCOPE_CHANGED);
            var body=JSON.createObjectNode();body.putArray("repository_ids").add(new BigInteger(request.scope().repositoryId()));body.putObject("permissions").put("actions","read").put("metadata","read");
            var minted=s.json("/app/installations/"+request.scope().installationId()+"/access_tokens",signing.token(),body.toString(),201);
            var permissions=object(minted,"permissions");
            if(!new HashSet<>(permissions.propertyNames()).equals(Set.of("actions","metadata")) || !"read".equals(text(permissions,"actions")) || !"read".equals(text(permissions,"metadata")))throw new CaptureFailure(UNAVAILABLE);
            var token=text(minted,"token");org.pipesor.ingestion.github.application.ports.GitHubUserTokens.token(token);s.expiry=Instant.parse(text(minted,"expires_at"));s.check();
            var repositoryResponse=s.call(origin.resolve("/installation/repositories?per_page=100&page=1"),token,null,200,maxBytes,false);
            if(!repositoryResponse.headers().allValues("Link").isEmpty())throw new CaptureFailure(UNAVAILABLE);
            var repositories=parse(repositoryResponse.body());
            var repos=repositories.get("repositories");
            if(integer(repositories,"total_count")!=1 || repos==null || !repos.isArray() || repos.size()!=1)throw new CaptureFailure(SCOPE_CHANGED);
            if(repositories.has("incomplete_results") && (!repositories.get("incomplete_results").isBoolean() || repositories.get("incomplete_results").booleanValue()))throw new CaptureFailure(UNAVAILABLE);
            var repository=repos.get(0);s.repository(repository);var fullName=text(repository,"full_name");
            if(!fullName.matches("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+") || Set.of(".","..").contains(fullName.split("/",-1)[1]))throw new CaptureFailure(UNAVAILABLE);
            var base="/repos/"+fullName;var runPath=base+"/actions/runs/"+request.runId()+"/attempts/"+request.attempt();
            var raw=s.json(runPath,token,null,200);s.run(raw);var sourceVersion=version(raw);
            var jobs=new ArrayList<Job>();var seen=new HashSet<String>();Integer total=null;
            for(int page=1;;page++) {
                if(page>maxPages)throw new CaptureFailure(UNAVAILABLE);
                var response=s.call(origin.resolve(runPath+"/jobs?per_page="+pageSize+"&page="+page),token,null,200,maxBytes,false);
                var parsed=parse(response.body());int count=integer(parsed,"total_count");var items=parsed.get("jobs");
                if(count>maxJobs || total!=null&&total!=count || items==null || !items.isArray() || items.size()>pageSize)throw new CaptureFailure(UNAVAILABLE);total=count;
                for(var item:items) {
                    var job=id(item,"id");if(!seen.add(job) || !request.runId().equals(id(item,"run_id")) || integer(item,"run_attempt")!=request.attempt())throw new CaptureFailure(UNAVAILABLE);
                    var steps=new ArrayList<Step>();var numbers=new HashSet<Integer>();var array=item.get("steps");if(array==null || !array.isArray())throw new CaptureFailure(UNAVAILABLE);
                    for(var step:array){int n=integer(step,"number");if(n<1||!numbers.add(n))throw new CaptureFailure(UNAVAILABLE);steps.add(new Step(n,text(step,"name"),text(step,"status"),nullable(step,"conclusion"),time(step,"started_at"),time(step,"completed_at")));}
                    jobs.add(new Job(job,text(item,"name"),text(item,"status"),nullable(item,"conclusion"),time(item,"started_at"),time(item,"completed_at"),steps));
                }
                if(jobs.size()>total)throw new CaptureFailure(UNAVAILABLE);
                if(jobs.size()==total){if(response.headers().allValues("Link").stream().anyMatch(v->v.contains("rel=\"next\"")))throw new CaptureFailure(UNAVAILABLE);break;}
                if(items.isEmpty())throw new CaptureFailure(UNAVAILABLE);
                // Pages are constructed locally; provider continuation URLs are never followed.
            }
            s.sameVersion(runPath,token,sourceVersion);s.fence(base,token);var run=new Run(request,fullName,nullable(raw,"head_branch"),text(raw,"head_sha"),text(raw,"status"),nullable(raw,"conclusion"),requiredTime(raw,"created_at"),requiredTime(raw,"updated_at"),time(raw,"run_started_at"),jobs);
            byte[] log=new byte[0];
            if(selectedJob!=null){
                if(!seen.contains(selectedJob))throw new CaptureFailure(SCOPE_CHANGED);
                var job=s.json(base+"/actions/jobs/"+selectedJob,token,null,200);
                if(!selectedJob.equals(id(job,"id")) || !request.runId().equals(id(job,"run_id")) || integer(job,"run_attempt")!=request.attempt())throw new CaptureFailure(SCOPE_CHANGED);
                var redirect=s.call(origin.resolve(base+"/actions/jobs/"+selectedJob+"/logs"),token,null,302,maxBytes,false);
                var locations=redirect.headers().allValues("Location");if(locations.size()!=1)throw new CaptureFailure(UNAVAILABLE);
                var target=URI.create(locations.getFirst());
                if(!"https".equals(target.getScheme()) || target.getHost()==null || !downloadHosts.contains(target.getHost().toLowerCase(Locale.ROOT)) || target.getUserInfo()!=null || target.getFragment()!=null || target.getPort()!=-1)throw new CaptureFailure(UNAVAILABLE);
                var download=s.call(target,null,null,200,maxLogBytes,true);var content=download.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().toLowerCase(Locale.ROOT);
                if(!Set.of("text/plain","application/octet-stream").contains(content) || download.headers().firstValue("Content-Encoding").filter(v->!v.equalsIgnoreCase("identity")).isPresent())throw new CaptureFailure(UNAVAILABLE);
                var charset=download.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                for(var parameter:charset.split(";"))if(parameter.strip().startsWith("charset=")&&!Set.of("charset=utf-8","charset=\"utf-8\"").contains(parameter.strip()))throw new CaptureFailure(UNAVAILABLE);
                log=download.body();
                try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(log)); }
                catch(java.nio.charset.CharacterCodingException invalidText){throw new CaptureFailure(UNAVAILABLE);}
                s.sameVersion(runPath,token,sourceVersion);s.fence(base,token);
            }
            s.authorized();s.check();return new Log(run,selectedJob,log);
        }catch(CaptureFailure failure){throw failure;}catch(RuntimeException privateFailure){throw new CaptureFailure(UNAVAILABLE);}
    }
    private final class Session {
        final Request request;final long started=System.nanoTime();Instant expiry;int remaining=maxBytes;
        Session(Request request){this.request=request;}
        void authorized(){try {if(TransactionSynchronizationManager.isActualTransactionActive())throw new CaptureFailure(INVALID_STATE);var state=authority.verify(request.scope());if(state==CurrentMachineScope.Decision.CHANGED_OR_LOST)throw new CaptureFailure(SCOPE_CHANGED);if(state!=CurrentMachineScope.Decision.AUTHORIZED)throw new CaptureFailure(UNAVAILABLE);}catch(CaptureFailure f){throw f;}catch(RuntimeException e){throw new CaptureFailure(UNAVAILABLE);}}
        void check(){if(System.nanoTime()-started>=budget.toNanos() || expiry==null || !expiry.isAfter(clock.instant()))throw new CaptureFailure(UNAVAILABLE);}
        void repository(JsonNode repo){if(!request.scope().repositoryId().equals(id(repo,"id")) || !request.scope().accountId().equals(id(object(repo,"owner"),"id")))throw new CaptureFailure(SCOPE_CHANGED);}
        void run(JsonNode run){if(!request.runId().equals(id(run,"id")) || !request.scope().workflowId().equals(id(run,"workflow_id")) || integer(run,"run_attempt")!=request.attempt())throw new CaptureFailure(SCOPE_CHANGED);repository(object(run,"repository"));}
        void fence(String base,String token){repository(json(base,token,null,200));}
        void sameVersion(String path,String token,SourceVersion expected){var latest=json(path,token,null,200);run(latest);if(!expected.equals(version(latest)))throw new CaptureFailure(UNAVAILABLE);}
        JsonNode json(String path,String token,String body,int status){return parse(call(origin.resolve(path),token,body,status,maxBytes,false).body());}
        HttpResponse<byte[]> call(URI target,String token,String body,int expected,int limit,boolean download){
            CompletableFuture<HttpResponse<byte[]>> pending=null;
            try {
                authorized();check();long nanos=Math.min(budget.toNanos()-(System.nanoTime()-started),Duration.between(clock.instant(),expiry).toNanos());
                var builder=HttpRequest.newBuilder(target).timeout(Duration.ofNanos(nanos));
                if(!download){builder.header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version",version).header("Authorization","Bearer "+token);}
                if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
                int available=download?limit:Math.min(limit,remaining);if(available<1)throw new CaptureFailure(UNAVAILABLE);
                pending=client.sendAsync(builder.build(),info->new Bounded(available));var response=pending.get(nanos,TimeUnit.NANOSECONDS);check();authorized();check();if(!download)remaining-=response.body().length;
                if(response.statusCode()==404)throw new CaptureFailure(SCOPE_CHANGED);
                if(response.statusCode()!=expected || response.headers().firstValue("X-GitHub-SSO").isPresent())throw new CaptureFailure(UNAVAILABLE);
                if(expected!=302&&!download&&!Set.of("application/json","application/vnd.github+json").contains(response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().toLowerCase(Locale.ROOT)))throw new CaptureFailure(UNAVAILABLE);
                return response;
            }catch(CaptureFailure f){throw f;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CaptureFailure(UNAVAILABLE);}catch(Exception e){throw new CaptureFailure(UNAVAILABLE);}finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
        }
    }
    private record SourceVersion(String branch,String sha,String status,String conclusion,Instant created,Instant updated,Instant started) {}
    private static SourceVersion version(JsonNode n){return new SourceVersion(nullable(n,"head_branch"),text(n,"head_sha"),text(n,"status"),nullable(n,"conclusion"),requiredTime(n,"created_at"),requiredTime(n,"updated_at"),time(n,"run_started_at"));}
    private static JsonNode parse(byte[] raw){var n=JSON.readTree(raw);if(n==null||!n.isObject())throw new CaptureFailure(UNAVAILABLE);return n;}
    private static JsonNode object(JsonNode n,String field){var value=n.get(field);if(value==null||!value.isObject())throw new CaptureFailure(UNAVAILABLE);return value;}
    private static String text(JsonNode n,String field){var value=n.get(field);if(value==null||!value.isString()||value.asString().isBlank())throw new CaptureFailure(UNAVAILABLE);return value.asString();}
    private static String nullable(JsonNode n,String field){if(!n.has(field))throw new CaptureFailure(UNAVAILABLE);return n.get(field).isNull()?null:text(n,field);}
    private static String id(JsonNode n,String field){var v=n.get(field);if(v==null||!v.isIntegralNumber()||!v.bigIntegerValue().toString().matches("[1-9][0-9]*"))throw new CaptureFailure(UNAVAILABLE);return v.bigIntegerValue().toString();}
    private static int integer(JsonNode n,String field){var v=n.get(field);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<0)throw new CaptureFailure(UNAVAILABLE);return v.intValue();}
    private static Instant time(JsonNode n,String field){var v=nullable(n,field);return v==null?null:Instant.parse(v);}
    private static Instant requiredTime(JsonNode n,String field){return Instant.parse(text(n,field));}
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> body=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final int limit;Flow.Subscription subscription;
        Bounded(int limit){this.limit=limit;}public CompletionStage<byte[]> getBody(){return body;}public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}public void onNext(List<ByteBuffer> buffers){for(var b:buffers){if(b.remaining()>limit-bytes.size()){subscription.cancel();body.completeExceptionally(new CaptureFailure(UNAVAILABLE));return;}while(b.hasRemaining())bytes.write(b.get());}subscription.request(1);}public void onError(Throwable e){body.completeExceptionally(new CaptureFailure(UNAVAILABLE));}public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
