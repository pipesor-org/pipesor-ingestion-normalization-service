package org.pipesor.ingestion.github.interfaces.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import org.pipesor.ingestion.capture.application.commandhandlers.RequestGitHubCapture;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.configuration.ApiProperties;
import org.pipesor.ingestion.github.application.queries.VerifyAccessQuery;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.pipesor.ingestion.shared.interfaces.rest.ApiFailure;
import org.pipesor.ingestion.shared.interfaces.rest.RequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "ingestion.local-capture.enabled", havingValue = "true")
public final class LocalRunAnalysisController {
    private final CaptureScope scope;
    private final RequestGitHubCapture capture;
    private final ObjectProvider<GitHubQueries> github;
    private final ApiProperties properties;
    private final Clock clock;
    private final GitHubMachineCapture machine;
    private final GitHubFileAuthority machineAuthority;
    private final String selectedRun;
    private final int selectedAttempt;

    public LocalRunAnalysisController(CaptureScope scope, RequestGitHubCapture capture,
            ObjectProvider<GitHubQueries> github, ApiProperties properties, Clock clock,
            GitHubMachineCapture machine, GitHubFileAuthority machineAuthority, @Value("${ingestion.local-capture.run}") String selectedRun,
            @Value("${ingestion.local-capture.attempt:1}") int selectedAttempt) {
        this.scope = scope;
        this.capture = capture;
        this.github = github;
        this.properties = properties;
        this.clock = clock;
        this.machine = machine;
        this.machineAuthority = machineAuthority;
        this.selectedRun = CaptureScope.external(selectedRun);
        if (selectedAttempt < 1) throw new IllegalArgumentException("Invalid local capture attempt");
        this.selectedAttempt = selectedAttempt;
    }

    @GetMapping("/api/v1/github/local-analysis")
    public ResponseEntity<Candidate> discover(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        var context = RequestContext.self(token, request, properties.limits(), clock);
        authorize(context);
        try {
            var selected = new GitHubMachineCapture.Request(scope, selectedRun, selectedAttempt, UUID.randomUUID().toString());
            var run = machine.readRun(selected);
            if (!selected.equals(run.request()) || !"completed".equals(run.status())
                    || !("failure".equals(run.conclusion()) || "timed_out".equals(run.conclusion()))) {
                throw new ApiFailure(404, "NOT_FOUND");
            }
            authorize(context);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new Candidate(scope.repositoryId(), run.fullName(), scope.workflowId(), selectedRun,
                            selectedAttempt, run.conclusion(), run.updatedAt()));
        } catch (CaptureFailure failure) {
            throw failure.reason() == CaptureFailure.Reason.SCOPE_CHANGED
                    ? new ApiFailure(403, "DENIED") : ApiFailure.unavailable();
        }
    }

    @PostMapping("/api/v1/github/repositories/{repositoryId}/workflows/{workflowId}/runs/{runId}/attempts/{attempt}/analysis")
    public ResponseEntity<Analysis> analyze(@AuthenticationPrincipal Jwt token, HttpServletRequest request,
            @PathVariable String repositoryId, @PathVariable String workflowId, @PathVariable String runId,
            @PathVariable int attempt) {
        var context = RequestContext.self(token, request, properties.limits(), clock);
        if (!scope.repositoryId().equals(repositoryId) || !scope.workflowId().equals(workflowId)
                || !selectedRun.equals(runId) || selectedAttempt != attempt) {
            throw new ApiFailure(403, "DENIED");
        }
        Runnable authority = () -> authorize(context);
        authority.run();
        try {
            var receipt = capture.handle(scope, runId, attempt, machineAuthority.completedReplayUntil(scope), authority);
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .body(new Analysis(runId, attempt, receipt.phase()));
        } catch (CaptureFailure failure) {
            throw switch (failure.reason()) {
                case INVALID_INPUT -> new ApiFailure(422, "UNSUPPORTED_EVIDENCE");
                case SCOPE_CHANGED -> new ApiFailure(403, "DENIED");
                case CONFLICT, STALE_CLAIM -> new ApiFailure(409, "CAPTURE_CONFLICT");
                default -> ApiFailure.unavailable();
            };
        }
    }

    private void authorize(RequestContext context) {
        context.requireLive(clock);
        if (!scope.tenantId().toString().equals(context.access().tenantId())) {
            throw new ApiFailure(403, "DENIED");
        }
        var provider = github.getIfAvailable();
        if (provider == null) throw ApiFailure.unavailable();
        var actor = context.access();
        var operation = new AccessContext(actor.tenantId(), actor.actorRef(), actor.subjectRef(),
                AccessOperation.OPERATE, actor.correlationId());
        var repository = new RepositoryTarget(scope.repositoryId(), scope.installationId());
        var observed = provider.verifyAccess(new VerifyAccessQuery(operation, Set.of(repository)));
        context.requireLive(clock);
        if (observed == null || !operation.equals(observed.context()) || observed.link() == null
                || observed.link().generationId() == null || !actor.tenantId().equals(observed.link().tenantId())
                || !actor.subjectRef().equals(observed.link().subjectRef()) || observed.observedAt() == null
                || observed.observedAt().isBefore(context.startedAt()) || observed.observedAt().isAfter(clock.instant())) {
            throw ApiFailure.unavailable();
        }
        var access = observed.repositories().get(repository);
        if (access == null || !access.admitted() || !access.readable() || !access.administrator()
                || !scope.accountId().equals(access.accountId())) throw new ApiFailure(403, "DENIED");
    }

    public record Candidate(String repositoryId, String repository, String workflowId, String runId,
            int attempt, String conclusion, Instant updatedAt) {}

    public record Analysis(String runId, int attempt, String phase) {}
}
