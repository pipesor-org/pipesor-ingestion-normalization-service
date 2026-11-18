package org.pipesor.ingestion.capture.infrastructure.configuration;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.application.ports.CurrentMachineScope.Decision;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.*;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import static org.junit.jupiter.api.Assertions.*;

class GitHubFileAuthorityTests {
    @TempDir Path directory;
    static final UUID TENANT=UUID.fromString("11111111-1111-4111-8111-111111111111"),OTHER=UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final Instant NOW=Instant.parse("2026-11-10T12:00:00Z");
    static final String HUMAN="{\"tenantId\":\""+TENANT+"\",\"appId\":\"120\",\"accountId\":\"240\",\"installationId\":\"360\",\"repositoryIds\":[\"480000000000000000001\"]}";
    static final String MACHINE="{\"captureDomain\":\"pilot\",\"tenantId\":\""+TENANT+"\",\"appId\":\"120\",\"accountId\":\"240\",\"installationId\":\"360\",\"repositoryId\":\"480000000000000000001\",\"workflowId\":\"590000000000000000002\",\"scopeRevision\":\"machine-r7\",\"allowedActions\":[\"completed\"],\"replayWindow\":\"PT20M\"}";
    static final String VALID="{\"formatVersion\":\"1\",\"revision\":\"document-r2\",\"humanAdmissions\":["+HUMAN+"],\"machineScopes\":["+MACHINE+"]}";
    static final CaptureScope SCOPE=new CaptureScope("pilot",TENANT,"120","240","360","480000000000000000001","590000000000000000002","machine-r7");
    Path file;
    @BeforeEach void setup() throws Exception {file=directory.resolve("authority.json");Files.writeString(file,VALID);}
    GitHubFileAuthority authority(){return new GitHubFileAuthority(file,"120",Clock.fixed(NOW,ZoneOffset.UTC),10000,20);}
    AccessContext context(UUID tenant){return new AccessContext(tenant.toString(),tenant+":actor",tenant+":actor",AccessOperation.VIEW,"corr-file");}
    ReadOnlyGitHub.Identity identity(UUID tenant){var c=context(tenant);return new ReadOnlyGitHub.Identity(c,new GitHubLink(c.tenantId(),c.subjectRef(),"777","link-r1",NOW,UUID.randomUUID()));}
    GitHubRepositorySelection.Request selection(UUID tenant){return new GitHubRepositorySelection.Request(context(tenant),Set.of(SCOPE.repositoryId(),"999"));}
    InstallationAdmission.Request admission(){return new InstallationAdmission.Request(identity(TENANT),SCOPE.accountId(),new RepositoryTarget(SCOPE.repositoryId(),SCOPE.installationId()));}
    Source source(String action){return new Source(action,SCOPE.installationId(),SCOPE.accountId(),SCOPE.repositoryId(),SCOPE.workflowId(),"680000000000000000003",3);}
    HookContext hook(UUID tenant,String domain){return new HookContext(domain,tenant,"120");}
    void replace(String body) throws Exception {var temporary=directory.resolve("next.json");Files.writeString(temporary,body);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    @Test void exposesOnlyExplicitHumanBindingsAndPreservesRequestedDeniedTargets() {
        var a=authority();var result=a.resolve(selection(TENANT));assertEquals(NOW,result.observedAt());assertEquals(selection(TENANT),result.request());
        assertEquals(new RepositoryTarget(SCOPE.repositoryId(),"360"),result.value().get(SCOPE.repositoryId()).orElseThrow());assertTrue(result.value().get("999").isEmpty());
        assertTrue(a.read(admission()).value());assertTrue(a.resolve(selection(OTHER)).value().values().stream().allMatch(Optional::isEmpty));
        assertFalse(a.read(new InstallationAdmission.Request(identity(OTHER),"240",admission().repository())).value());
        assertFalse(a.read(new InstallationAdmission.Request(identity(TENANT),"241",admission().repository())).value());
        assertFalse(a.read(new InstallationAdmission.Request(identity(TENANT),"240",new RepositoryTarget(SCOPE.repositoryId(),"361"))).value());
    }
    @Test void machineAndWebhookUseExactScopeActionAndTrustedHookContext() {
        var a=authority();assertEquals(Decision.AUTHORIZED,a.verify(SCOPE));var auth=a.webhook(hook(TENANT,"pilot")).apply(source("completed"));
        assertEquals(SCOPE,auth.scope());assertEquals(NOW.plusSeconds(1200),auth.replayUntil());assertNull(a.webhook(hook(TENANT,"pilot")).apply(source("in_progress")));
        assertNull(a.webhook(hook(OTHER,"pilot")).apply(source("completed")));assertNull(a.webhook(hook(TENANT,"other-domain")).apply(source("completed")));
        assertNull(a.webhook(hook(TENANT,"pilot")).apply(new Source("completed","360","240",SCOPE.repositoryId(),"591","680",1)));
        assertEquals(Decision.CHANGED_OR_LOST,a.verify(new CaptureScope("pilot",TENANT,"120","240","360",SCOPE.repositoryId(),SCOPE.workflowId(),"machine-r6")));
    }
    @Test void protectedEvidenceScopeUsesTrustedRouteAndRechecksExactCompletedBinding() throws Exception {
        var a = authority();
        var route = hook(TENANT, "pilot");
        assertEquals(Decision.AUTHORIZED, a.verifyProtectedEvidenceScope(route, SCOPE.context(), SCOPE.pipeline()));
        assertEquals(Decision.CHANGED_OR_LOST,
                a.verifyProtectedEvidenceScope(hook(OTHER, "pilot"), SCOPE.context(), SCOPE.pipeline()));
        assertEquals(Decision.CHANGED_OR_LOST,
                a.verifyProtectedEvidenceScope(hook(TENANT, "other-domain"), SCOPE.context(), SCOPE.pipeline()));
        assertEquals(Decision.UNAVAILABLE,
                a.verifyProtectedEvidenceScope(new HookContext("pilot", TENANT, "121"), SCOPE.context(), SCOPE.pipeline()));
        var wrongAccount = new WorkflowObservation.CaptureContext(TENANT, "241", SCOPE.installationId(), SCOPE.scopeRevision());
        var wrongInstallation = new WorkflowObservation.CaptureContext(TENANT, SCOPE.accountId(), "361", SCOPE.scopeRevision());
        var wrongRepository = new WorkflowObservation.Pipeline("481", SCOPE.workflowId());
        var wrongWorkflow = new WorkflowObservation.Pipeline(SCOPE.repositoryId(), "591");
        assertEquals(Decision.CHANGED_OR_LOST, a.verifyProtectedEvidenceScope(route, wrongAccount, SCOPE.pipeline()));
        assertEquals(Decision.CHANGED_OR_LOST, a.verifyProtectedEvidenceScope(route, wrongInstallation, SCOPE.pipeline()));
        assertEquals(Decision.CHANGED_OR_LOST, a.verifyProtectedEvidenceScope(route, SCOPE.context(), wrongRepository));
        assertEquals(Decision.CHANGED_OR_LOST, a.verifyProtectedEvidenceScope(route, SCOPE.context(), wrongWorkflow));
        replace(VALID.replace("machine-r7", "machine-r8"));
        assertEquals(Decision.CHANGED_OR_LOST, a.verifyProtectedEvidenceScope(route, SCOPE.context(), SCOPE.pipeline()));
        replace(VALID.replace("completed", "requested"));
        assertEquals(Decision.CHANGED_OR_LOST, a.verifyProtectedEvidenceScope(route, SCOPE.context(), SCOPE.pipeline()));
        replace("private-invalid-file");
        assertEquals(Decision.UNAVAILABLE, a.verifyProtectedEvidenceScope(route, SCOPE.context(), SCOPE.pipeline()));
    }
    @Test void atomicReplacementRevokesWithoutLastGoodCacheAndRootRevisionIsNotImplicitScopeRevision() throws Exception {
        var a=authority();assertEquals(Decision.AUTHORIZED,a.verify(SCOPE));replace(VALID.replace("document-r2","document-r3"));assertEquals(Decision.AUTHORIZED,a.verify(SCOPE));
        replace(VALID.replace("machine-r7","machine-r8"));assertEquals(Decision.CHANGED_OR_LOST,a.verify(SCOPE));
        replace("{\"formatVersion\":\"1\",\"revision\":\"deny-r4\",\"humanAdmissions\":[],\"machineScopes\":[]}");
        assertEquals(Decision.CHANGED_OR_LOST,a.verify(SCOPE));assertFalse(a.read(admission()).value());assertTrue(a.resolve(selection(TENANT)).value().values().stream().allMatch(Optional::isEmpty));assertNull(a.webhook(hook(TENANT,"pilot")).apply(source("completed")));
    }
    @ParameterizedTest @ValueSource(strings={"malformed","trailing","duplicate-root","unknown-root","schema","missing-array","unknown-human","ambiguous-human","duplicate-machine","domain-owner","app-crossing","account-conflict","invalid-action","duplicate-action","zero-replay","negative-replay","bad-uuid","wildcard","unknown-machine"})
    void wholeInvalidFileFailsUnavailableForEveryConsumerWithoutRetainingPriorAuthorization(String invalid) throws Exception {
        var a=authority();assertEquals(Decision.AUTHORIZED,a.verify(SCOPE));
        var body=switch(invalid){
            case "malformed" -> "private-source-not-json";
            case "trailing" -> VALID+" {}";
            case "duplicate-root" -> VALID.replace("\"formatVersion\":\"1\"","\"formatVersion\":\"1\",\"formatVersion\":\"1\"");
            case "unknown-root" -> VALID.replace("\"revision\"","\"privateUnexpected\":true,\"revision\"");
            case "schema" -> VALID.replace("\"formatVersion\":\"1\"","\"formatVersion\":\"2\"");
            case "missing-array" -> "{\"formatVersion\":\"1\",\"revision\":\"r1\",\"machineScopes\":[]}";
            case "unknown-human" -> VALID.replace(HUMAN,HUMAN.replace("\"accountId\"","\"privateUnexpected\":true,\"accountId\""));
            case "ambiguous-human" -> VALID.replace(HUMAN,HUMAN+","+HUMAN.replace("\"360\"","\"361\""));
            case "duplicate-machine" -> VALID.replace(MACHINE,MACHINE+","+MACHINE.replace("machine-r7","machine-r8"));
            case "domain-owner" -> VALID.replace(MACHINE,MACHINE+","+MACHINE.replace(TENANT.toString(),OTHER.toString()));
            case "app-crossing" -> VALID.replace("\"120\"","\"121\"");
            case "account-conflict" -> VALID.replace(MACHINE,MACHINE.replace("\"240\"","\"241\""));
            case "invalid-action" -> VALID.replace("\"completed\"","\"*\"");
            case "duplicate-action" -> VALID.replace("\"completed\"","\"completed\",\"completed\"");
            case "zero-replay" -> VALID.replace("PT20M","PT0S");
            case "negative-replay" -> VALID.replace("PT20M","PT-1S");
            case "bad-uuid" -> VALID.replace(TENANT.toString(),"private-invalid-tenant");
            case "wildcard" -> VALID.replace("480000000000000000001","*");
            case "unknown-machine" -> VALID.replace(MACHINE,MACHINE.replace("\"workflowId\"","\"privateUnexpected\":true,\"workflowId\""));
            default -> throw new AssertionError();
        };replace(body);
        var capture=assertThrowsExactly(CaptureFailure.class,()->a.verify(SCOPE));assertEquals(CaptureFailure.Reason.UNAVAILABLE,capture.reason());assertNull(capture.getCause());
        var access=assertThrowsExactly(GitHubAccessFailure.class,()->a.resolve(selection(TENANT)));assertEquals(GitHubAccessFailure.Reason.UNAVAILABLE,access.reason());assertNull(access.getCause());
        var admissionFailure=assertThrowsExactly(GitHubAccessFailure.class,()->a.read(admission()));assertEquals(GitHubAccessFailure.Reason.UNAVAILABLE,admissionFailure.reason());assertNull(admissionFailure.getCause());
        var webhookFailure=assertThrowsExactly(CaptureFailure.class,()->a.webhook(hook(TENANT,"pilot")).apply(source("completed")));assertEquals(CaptureFailure.Reason.UNAVAILABLE,webhookFailure.reason());assertNull(webhookFailure.getCause());
        assertFalse(capture.toString().contains("private"));assertFalse(access.toString().contains(file.toString()));
    }
    @Test void missingFileAndResourceLimitsAreUnavailableNotSuccessfulEmptyResults() throws Exception {
        var a=authority();assertTrue(a.read(admission()).value());Files.delete(file);assertThrowsExactly(GitHubAccessFailure.class,()->a.resolve(selection(TENANT)));assertThrowsExactly(CaptureFailure.class,()->a.verify(SCOPE));
        replace(VALID);assertThrowsExactly(CaptureFailure.class,()->new GitHubFileAuthority(file,"120",Clock.systemUTC(),50,20).verify(SCOPE));assertThrowsExactly(CaptureFailure.class,()->new GitHubFileAuthority(file,"120",Clock.systemUTC(),10000,1).verify(SCOPE));
    }
    @Test void readinessRejectsNonRegularSourcesAndAllowsOperatorOwnedRegularSymlink() throws Exception {
        assertThrowsExactly(CaptureFailure.class,()->new GitHubFileAuthority(directory,"120",Clock.systemUTC(),10000,20).validate());
        var alias=directory.resolve("operator-link.json");Files.createSymbolicLink(alias,file);
        new GitHubFileAuthority(alias,"120",Clock.systemUTC(),10000,20).validate();
        var fifo=directory.resolve("pipe");var created=new ProcessBuilder("mkfifo",fifo.toString()).start();assertEquals(0,created.waitFor());
        assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertThrowsExactly(CaptureFailure.class,()->new GitHubFileAuthority(fifo,"120",Clock.systemUTC(),10000,20).validate()));
    }
    @Test void humanLinkSubjectTenantOrMissingGenerationCannotCrossRequestBinding() {
        var a=authority();var c=context(TENANT);
        for(var link:List.of(new GitHubLink(c.tenantId(),"other-subject","777","r1",NOW,UUID.randomUUID()),new GitHubLink(OTHER.toString(),c.subjectRef(),"777","r1",NOW,UUID.randomUUID()),new GitHubLink(c.tenantId(),c.subjectRef(),"777","r1",NOW))){
            assertThrowsExactly(GitHubAccessFailure.class,()->a.read(new InstallationAdmission.Request(new ReadOnlyGitHub.Identity(c,link),"240",admission().repository())));
        }
        assertEquals("GitHubFileAuthority[configured]",a.toString());
    }
}
