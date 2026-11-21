package org.pipesor.ingestion.capture.infrastructure.configuration;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.pipesor.ingestion.capture.application.ports.CurrentMachineScope;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.*;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Operator-owned atomic file snapshots; no last-good authorization cache, admin API or restore proof. */
public final class GitHubFileAuthority implements CurrentMachineScope,InstallationAdmission,GitHubRepositorySelection {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> ROOT=Set.of("formatVersion","revision","humanAdmissions","machineScopes");
    private static final Set<String> HUMAN=Set.of("tenantId","appId","accountId","installationId","repositoryIds");
    private static final Set<String> MACHINE=Set.of("captureDomain","tenantId","appId","accountId","installationId","repositoryId","workflowId","scopeRevision","allowedActions","replayWindow");
    private final Path file;
    private final String appId;
    private final Clock clock;
    private final int maxBytes,maxEntries;
    public GitHubFileAuthority(Path file,String expectedAppId,Clock clock,int maxBytes,int maxEntries) {
        this.file=Objects.requireNonNull(file);this.appId=CaptureScope.external(expectedAppId);this.clock=Objects.requireNonNull(clock);
        if(maxBytes<1||maxBytes==Integer.MAX_VALUE||maxEntries<1)throw new IllegalArgumentException("Explicit authority file limits required");
        this.maxBytes=maxBytes;this.maxEntries=maxEntries;
    }
    private record Admission(UUID tenant,String account,String installation,Set<String> repositories) {
        @Override public String toString(){return "Admission[private]";}
    }
    private record Machine(CaptureScope scope,Set<String> actions,Duration replay) {
        @Override public String toString(){return "MachineAuthority[private]";}
    }
    private record Snapshot(List<Admission> human,List<Machine> machines) {
        @Override public String toString(){return "AuthoritySnapshot[private]";}
    }
    /** Readiness validates a file snapshot; it does not authorize a request or certify restoration. */
    public void validate(){try{load();}catch(RuntimeException unavailable){throw captureUnavailable();}}
    @Override public Decision verify(CaptureScope expected) {
        try {var snapshot=load();return snapshot.machines().stream().anyMatch(m->m.scope().equals(expected))?Decision.AUTHORIZED:Decision.CHANGED_OR_LOST;}
        catch(RuntimeException unavailable){throw captureUnavailable();}
    }
    /** The caller supplies the trusted capture route, independently of the event body. */
    public Decision verifyProtectedEvidenceScope(HookContext trustedRoute, WorkflowObservation.CaptureContext capture,
            WorkflowObservation.Pipeline pipeline) {
        try {
            if (!appId.equals(trustedRoute.appId())) return Decision.UNAVAILABLE;
            if (!trustedRoute.tenantId().equals(capture.tenantId())) return Decision.CHANGED_OR_LOST;
            var expected = new CaptureScope(trustedRoute.captureDomain(), trustedRoute.tenantId(),
                    trustedRoute.appId(), capture.githubAccountId(), capture.githubInstallationId(),
                    pipeline.githubRepositoryId(), pipeline.githubWorkflowId(), capture.scopeRevision());
            boolean allowed = load().machines().stream()
                    .anyMatch(machine -> expected.equals(machine.scope()) && machine.actions().contains("completed"));
            return allowed ? Decision.AUTHORIZED : Decision.CHANGED_OR_LOST;
        } catch (RuntimeException unavailable) {
            return Decision.UNAVAILABLE;
        }
    }
    public Instant completedReplayUntil(CaptureScope scope) {
        try {
            return load().machines().stream()
                    .filter(machine -> scope.equals(machine.scope()) && machine.actions().contains("completed"))
                    .findFirst().map(machine -> clock.instant().plus(machine.replay()))
                    .orElseThrow(() -> new CaptureFailure(CaptureFailure.Reason.SCOPE_CHANGED));
        } catch (CaptureFailure denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw captureUnavailable();
        }
    }

    @Override public Observation<InstallationAdmission.Request,Boolean> read(InstallationAdmission.Request request) {
        try {
            Objects.requireNonNull(request);var identity=Objects.requireNonNull(request.identity());var context=identity.context();var link=identity.link();
            var tenant=tenant(context.tenantId());
            if(!tenant.equals(tenant(link.tenantId()))||!context.subjectRef().equals(link.subjectRef())||link.generationId()==null)throw new IllegalArgumentException();
            CaptureScope.external(request.accountId());CaptureScope.external(request.repository().repositoryId());CaptureScope.external(request.repository().installationId());
            var granted=load().human().stream().anyMatch(a->a.tenant().equals(tenant)&&a.account().equals(request.accountId())&&a.installation().equals(request.repository().installationId())&&a.repositories().contains(request.repository().repositoryId()));
            return new Observation<>(request,granted,clock.instant());
        }catch(RuntimeException unavailable){throw accessUnavailable();}
    }
    @Override public Observation<GitHubRepositorySelection.Request,Map<String,Optional<RepositoryTarget>>> resolve(GitHubRepositorySelection.Request request) {
        try {
            Objects.requireNonNull(request);var tenant=tenant(request.context().tenantId());var snapshot=load();var result=new HashMap<String,Optional<RepositoryTarget>>();
            if(request.repositoryIds().size()>maxEntries)throw new IllegalArgumentException();
            for(var id:request.repositoryIds()) {
                CaptureScope.external(id);var matched=snapshot.human().stream().filter(a->a.tenant().equals(tenant)&&a.repositories().contains(id)).findFirst();
                result.put(id,matched.map(a->new RepositoryTarget(id,a.installation())));
            }
            return new Observation<>(request,Map.copyOf(result),clock.instant());
        }catch(RuntimeException unavailable){throw accessUnavailable();}
    }
    public Function<Source,Authorization> webhook(HookContext hook) {
        Objects.requireNonNull(hook);
        if(!appId.equals(hook.appId()))throw new IllegalArgumentException("Webhook authority binding required");
        return source->{
            try {
                Objects.requireNonNull(source);CaptureScope.external(source.installationId());CaptureScope.external(source.accountId());CaptureScope.external(source.repositoryId());CaptureScope.external(source.workflowId());CaptureScope.external(source.runId());if(source.runAttempt()<1)throw new IllegalArgumentException();
                var matches=load().machines().stream().filter(m->{var s=m.scope();return s.captureDomain().equals(hook.captureDomain())&&s.tenantId().equals(hook.tenantId())&&s.appId().equals(hook.appId())&&s.accountId().equals(source.accountId())&&s.installationId().equals(source.installationId())&&s.repositoryId().equals(source.repositoryId())&&s.workflowId().equals(source.workflowId())&&m.actions().contains(source.action());}).toList();
                if(matches.isEmpty())return null;
                var matched=matches.getFirst();return new Authorization(matched.scope(),clock.instant().plus(matched.replay()));
            }catch(RuntimeException unavailable){throw captureUnavailable();}
        };
    }
    private Snapshot load() {
        if(!Files.isRegularFile(file))throw new IllegalStateException("GitHub authority unavailable");
        try(var stream=Files.newInputStream(file)) {
            var bytes=stream.readNBytes(maxBytes+1);if(bytes.length>maxBytes)throw new IllegalArgumentException();
            var root=JSON.readTree(bytes);fields(root,ROOT);if(!"1".equals(text(root,"formatVersion")))throw new IllegalArgumentException();CaptureScope.safe(text(root,"revision"));
            var human=array(root,"humanAdmissions");var machine=array(root,"machineScopes");if((long)human.size()+machine.size()>maxEntries)throw new IllegalArgumentException();
            var admissions=new ArrayList<Admission>();var machines=new ArrayList<Machine>();var repositories=new HashSet<String>();var scopes=new HashSet<List<Object>>();var domains=new HashMap<String,UUID>();var accounts=new HashMap<String,String>();long entries=machine.size();
            for(var item:human) {
                fields(item,HUMAN);checkApp(item);var tenant=tenant(text(item,"tenantId"));var account=id(item,"accountId");var installation=id(item,"installationId");account(accounts,installation,account);
                var ids=new HashSet<String>();var requested=array(item,"repositoryIds");if(requested.isEmpty())throw new IllegalArgumentException();
                entries+=requested.size();if(entries>maxEntries)throw new IllegalArgumentException();
                for(var value:requested){if(!value.isString())throw new IllegalArgumentException();var id=CaptureScope.external(value.asString());if(!ids.add(id)||!repositories.add(tenant+":"+id))throw new IllegalArgumentException();}
                admissions.add(new Admission(tenant,account,installation,Set.copyOf(ids)));
            }
            for(var item:machine) {
                fields(item,MACHINE);checkApp(item);var scope=new CaptureScope(text(item,"captureDomain"),tenant(text(item,"tenantId")),appId,id(item,"accountId"),id(item,"installationId"),id(item,"repositoryId"),id(item,"workflowId"),text(item,"scopeRevision"));
                account(accounts,scope.installationId(),scope.accountId());
                var old=domains.putIfAbsent(scope.captureDomain(),scope.tenantId());if(old!=null&&!old.equals(scope.tenantId()))throw new IllegalArgumentException();
                var key=List.<Object>of(scope.captureDomain(),scope.tenantId(),scope.appId(),scope.accountId(),scope.installationId(),scope.repositoryId(),scope.workflowId());if(!scopes.add(key))throw new IllegalArgumentException();
                var actions=new HashSet<String>();for(var action:array(item,"allowedActions")){if(!action.isString()||!Set.of("requested","in_progress","completed").contains(action.asString())||!actions.add(action.asString()))throw new IllegalArgumentException();}
                if(actions.isEmpty())throw new IllegalArgumentException();var replay=Duration.parse(text(item,"replayWindow"));if(replay.isZero()||replay.isNegative())throw new IllegalArgumentException();
                machines.add(new Machine(scope,Set.copyOf(actions),replay));
            }
            return new Snapshot(List.copyOf(admissions),List.copyOf(machines));
        }catch(Exception privateFailure){throw new IllegalStateException("GitHub authority unavailable",null);}
    }
    private void checkApp(JsonNode item){if(!appId.equals(id(item,"appId")))throw new IllegalArgumentException();}
    private static void account(Map<String,String> accounts,String installation,String account){var previous=accounts.putIfAbsent(installation,account);if(previous!=null&&!previous.equals(account))throw new IllegalArgumentException();}
    private static UUID tenant(String raw){var value=UUID.fromString(raw);if(!value.toString().equalsIgnoreCase(raw)||value.equals(new UUID(0,0)))throw new IllegalArgumentException();return value;}
    private static void fields(JsonNode node,Set<String> fields){if(node==null||!node.isObject()||node.propertyNames().size()!=fields.size()||!fields.containsAll(node.propertyNames()))throw new IllegalArgumentException();}
    private static JsonNode array(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isArray())throw new IllegalArgumentException();return value;}
    private static String text(JsonNode node,String field){var value=node.get(field);if(value==null||!value.isString()||value.asString().isBlank())throw new IllegalArgumentException();return value.asString();}
    private static String id(JsonNode node,String field){return CaptureScope.external(text(node,field));}
    private static CaptureFailure captureUnavailable(){return new CaptureFailure(CaptureFailure.Reason.UNAVAILABLE);}
    private static GitHubAccessFailure accessUnavailable(){return new GitHubAccessFailure(GitHubAccessFailure.Reason.UNAVAILABLE);}
    @Override public String toString(){return "GitHubFileAuthority[configured]";}
}
