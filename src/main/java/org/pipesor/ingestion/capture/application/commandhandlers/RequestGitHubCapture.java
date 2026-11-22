package org.pipesor.ingestion.capture.application.commandhandlers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Accept;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Request;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.domain.services.ObservationFingerprint;

import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/**
 * Admits an authenticated provider observation after the caller has verified request authority.
 * It only records durable intent: the PreparationWorker prepares and the PublicationWorker publishes.
 */
public final class RequestGitHubCapture {
    private static final java.util.Set<String> CAPTURABLE = java.util.Set.of("failure", "timed_out", "success");
    private final CaptureHandler capture;
    private final GitHubMachineCapture github;

    public RequestGitHubCapture(CaptureHandler capture, GitHubMachineCapture github) {
        this.capture = java.util.Objects.requireNonNull(capture);
        this.github = java.util.Objects.requireNonNull(github);
    }

    public CaptureReceipt handle(CaptureScope scope, String runId, int attempt, Instant replayUntil,
            Runnable recheckCaller) {
        if (attempt < 1) throw new CaptureFailure(INVALID_INPUT);
        var request = new Request(scope, runId, attempt, UUID.randomUUID().toString());
        var run = github.readRun(request);
        if (run == null || !request.equals(run.request())) throw new CaptureFailure(CONFLICT);
        // Failed runs are analysed; successful runs are recorded so Pipeline can accept them as incident verification.
        if (!"completed".equals(run.status()) || !CAPTURABLE.contains(run.conclusion())) {
            throw new CaptureFailure(INVALID_INPUT);
        }
        recheckCaller.run();
        // The authenticated API observation has its own receipt identity, separate from webhook deliveries.
        var alias = captureKey(scope, runId, attempt);
        var authenticatedDigest = digest("github-api-on-demand", alias, run.updatedAt().toString(),
                run.commitSha(), run.status(), run.conclusion());
        return capture.accept(new Accept(scope, scope, "github_actions", "on-demand:" + alias,
                authenticatedDigest, runId, attempt, request.correlationId(), replayUntil));
    }

    public Optional<CaptureReceipt> find(CaptureScope scope, String runId, int attempt) {
        return capture.findDelivery(scope, "github_actions", "on-demand:" + captureKey(scope, runId, attempt));
    }

    /** Whether the published observation of this receipt recorded a failure (and therefore an incident). */
    public boolean observedFailure(CaptureReceipt receipt) {
        return capture.observedFailure(receipt);
    }

    private static String captureKey(CaptureScope scope, String runId, int attempt) {
        return digest(scope.tenantId().toString(), scope.appId(), scope.accountId(), scope.installationId(),
                scope.repositoryId(), scope.workflowId(), runId, Integer.toString(attempt), scope.scopeRevision());
    }

    private static String digest(String... fields) {
        return ObservationFingerprint.hash(String.join("\n", fields).getBytes(StandardCharsets.UTF_8));
    }
}
