package org.pipesor.ingestion.capture.application.commandhandlers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Accept;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Claim;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Request;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.domain.services.ObservationFingerprint;

import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** Admits an authenticated provider observation after the caller has verified request authority. */
public final class RequestGitHubCapture {
    private final CaptureHandler capture;
    private final GitHubMachineCapture github;
    private final PrepareGitHubCapture preparation;
    private final Clock clock;
    private final boolean publishLocally;

    public RequestGitHubCapture(CaptureHandler capture, GitHubMachineCapture github,
            PrepareGitHubCapture preparation, Clock clock, boolean publishLocally) {
        this.capture = capture;
        this.github = github;
        this.preparation = preparation;
        this.clock = clock;
        this.publishLocally = publishLocally;
    }

    public CaptureReceipt handle(CaptureScope scope, String runId, int attempt, Instant replayUntil,
            Runnable recheckCaller) {
        if (attempt < 1) throw new CaptureFailure(INVALID_INPUT);
        var request = new Request(scope, runId, attempt, UUID.randomUUID().toString());
        var run = github.readRun(request);
        if (run == null || !request.equals(run.request())) throw new CaptureFailure(CONFLICT);
        if (!"completed".equals(run.status()) || !("failure".equals(run.conclusion()) || "timed_out".equals(run.conclusion()))) {
            throw new CaptureFailure(INVALID_INPUT);
        }
        recheckCaller.run();
        // The authenticated API observation has its own receipt identity, separate from webhook deliveries.
        var alias = captureKey(scope, runId, attempt);
        var authenticatedDigest = digest("github-api-on-demand", alias, run.updatedAt().toString(),
                run.commitSha(), run.status(), run.conclusion());
        var receipt = capture.accept(new Accept(scope, scope, "github_actions", "on-demand:" + alias,
                authenticatedDigest, runId, attempt, request.correlationId(), replayUntil));
        if ("ACCEPTED".equals(receipt.phase())) {
            var now = clock.instant();
            receipt = preparation.prepare(new Claim(receipt.id(), UUID.randomUUID(), now.plusSeconds(90)),
                    now.plus(Duration.ofMinutes(15)), run, recheckCaller);
        }
        if (publishLocally && "PREPARED".equals(receipt.phase())) {
            for (int output = 0; output < 2; output++) {
                recheckCaller.run();
                var now = clock.instant();
                if (!capture.publishNext(receipt.id(), UUID.randomUUID(), now.plusSeconds(20), now.plusSeconds(1))) break;
            }
            return capture.findDelivery(scope, "github_actions", "on-demand:" + alias).orElseThrow();
        }
        return receipt;
    }

    public Optional<String> phase(CaptureScope scope, String runId, int attempt) {
        return capture.findDelivery(scope, "github_actions", "on-demand:" + captureKey(scope, runId, attempt))
                .map(CaptureReceipt::phase);
    }

    private static String captureKey(CaptureScope scope, String runId, int attempt) {
        return digest(scope.tenantId().toString(), scope.appId(), scope.accountId(), scope.installationId(),
                scope.repositoryId(), scope.workflowId(), runId, Integer.toString(attempt), scope.scopeRevision());
    }

    private static String digest(String... fields) {
        return ObservationFingerprint.hash(String.join("\n", fields).getBytes(StandardCharsets.UTF_8));
    }
}
