package org.pipesor.ingestion.capture.application.commandhandlers;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.ports.MachineScopes;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.domain.services.PipelineIds;
import org.pipesor.ingestion.github.application.queries.VerifyAccessQuery;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

/**
 * On-demand analysis of a failed run attempt selected by the user (MVP HTTP API v1). Authorization
 * requires ADR-020 OPERATE conditions: an admitted installation and repository that the user can read
 * and administers with their current GitHub link, within an operator-admitted machine scope.
 */
public final class GitHubAnalysisHandler {
    /** Authorization failure without disclosure of which condition failed. */
    /** ADR-023: the workflow has no ACTIVE capture scope yet (not registered, paused or not synced). */
    public static final class AnalysisPending extends RuntimeException {
        public AnalysisPending() { super("Capture scope pending", null, false, false); }
    }

    public static final class AnalysisDenied extends RuntimeException {
        public AnalysisDenied() { super("Analysis denied", null, false, false); }
    }

    public record Target(String repositoryId, String workflowId, String runId, int attempt) {
        public Target {
            CaptureScope.external(repositoryId); CaptureScope.external(workflowId); CaptureScope.external(runId);
            if (attempt < 1) throw new CaptureFailure(CaptureFailure.Reason.INVALID_INPUT);
        }
    }

    public record Analysis(UUID analysisId, String phase, String blockedReason, UUID executionId, UUID incidentId) { }

    private final MachineScopes scopes;
    private final RequestGitHubCapture capture;
    private final GitHubQueries github;
    private final String captureDomain;
    private final UUID tenant;
    private final Clock clock;

    public GitHubAnalysisHandler(MachineScopes scopes, RequestGitHubCapture capture, GitHubQueries github,
            String captureDomain, UUID tenant, Clock clock) {
        this.scopes = Objects.requireNonNull(scopes); this.capture = Objects.requireNonNull(capture);
        this.github = Objects.requireNonNull(github); this.captureDomain = CaptureScope.safe(captureDomain);
        this.tenant = Objects.requireNonNull(tenant); this.clock = Objects.requireNonNull(clock);
    }

    /** Records durable intent only; preparation and publication happen in the workers. */
    public Analysis request(AccessContext access, Target target, Instant startedAt, Runnable live) {
        var scope = scope(access, target);
        Runnable authority = () -> authorize(access, scope, startedAt, live);
        authority.run();
        var receipt = capture.handle(scope, target.runId(), target.attempt(), scopes.completedReplayUntil(scope), authority);
        return view(receipt, target);
    }

    public Optional<Analysis> status(AccessContext access, Target target, Instant startedAt, Runnable live) {
        var scope = scope(access, target);
        authorize(access, scope, startedAt, live);
        return capture.find(scope, target.runId(), target.attempt()).map(receipt -> view(receipt, target));
    }

    private CaptureScope scope(AccessContext access, Target target) {
        if (!tenant.toString().equals(access.tenantId())) throw new AnalysisDenied();
        return scopes.completedScope(captureDomain, tenant, target.repositoryId(), target.workflowId())
                .orElseThrow(AnalysisPending::new);
    }

    private void authorize(AccessContext actor, CaptureScope scope, Instant startedAt, Runnable live) {
        live.run();
        var operation = new AccessContext(actor.tenantId(), actor.actorRef(), actor.subjectRef(), AccessOperation.OPERATE,
                actor.correlationId());
        var repository = new RepositoryTarget(scope.repositoryId(), scope.installationId());
        var observed = github.verifyAccess(new VerifyAccessQuery(operation, Set.of(repository)));
        live.run();
        if (observed == null || !operation.equals(observed.context()) || observed.link() == null
                || observed.link().generationId() == null || !actor.tenantId().equals(observed.link().tenantId())
                || !actor.subjectRef().equals(observed.link().subjectRef()) || observed.observedAt() == null
                || observed.observedAt().isBefore(startedAt) || observed.observedAt().isAfter(clock.instant())) {
            throw new GitHubAccessFailure(GitHubAccessFailure.Reason.UNAVAILABLE);
        }
        var access = observed.repositories().get(repository);
        if (access == null || !access.admitted() || !access.readable() || !access.administrator()
                || !scope.accountId().equals(access.accountId())) throw new AnalysisDenied();
    }

    private static Analysis view(CaptureReceipt receipt, Target target) {
        var phase = switch (receipt.phase()) {
            case "ACCEPTED" -> receipt.claimOwner() == null ? "ACCEPTED" : "PREPARING";
            case "PREPARED" -> "PREPARING";
            case "PUBLISHED" -> "PUBLISHED";
            default -> "BLOCKED";
        };
        UUID execution = null, incident = null;
        if ("PUBLISHED".equals(phase)) {
            execution = PipelineIds.execution(target.repositoryId(), target.runId(), target.attempt());
            incident = PipelineIds.incident(execution);
        }
        // The ledger blocks receipts only when the admitted machine scope changed or was lost.
        return new Analysis(receipt.id(), phase, "BLOCKED".equals(phase) ? "SCOPE_CHANGED" : null, execution, incident);
    }
}
