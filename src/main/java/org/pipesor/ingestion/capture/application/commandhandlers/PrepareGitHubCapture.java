package org.pipesor.ingestion.capture.application.commandhandlers;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Claim;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Prepare;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture;
import org.pipesor.ingestion.capture.application.ports.ProtectedMessageEncoder;
import org.pipesor.ingestion.capture.application.services.FailedRunEvidenceProfile;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.commands.NormalizeObservationCommand;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.*;

/** One explicit receipt attempt. Source I/O and protection run outside ledger transactions. */
public final class PrepareGitHubCapture {
    private final CaptureHandler capture;
    private final GitHubMachineCapture github;
    private final FailedRunEvidenceProfile profile;
    private final NormalizeObservationHandler normalizer;
    private final ProtectedMessageEncoder encoder;
    private final Clock clock;
    private final int maxWireBytes;
    private final String protectionVersion;

    public PrepareGitHubCapture(CaptureHandler capture, GitHubMachineCapture github, FailedRunEvidenceProfile profile,
            NormalizeObservationHandler normalizer, ProtectedMessageEncoder encoder, Clock clock,
            int maxWireBytes, String protectionVersion) {
        this.capture = Objects.requireNonNull(capture); this.github = Objects.requireNonNull(github);
        this.profile = Objects.requireNonNull(profile); this.normalizer = Objects.requireNonNull(normalizer);
        this.encoder = Objects.requireNonNull(encoder); this.clock = Objects.requireNonNull(clock);
        if (maxWireBytes < 1) { throw new IllegalArgumentException("Explicit preparation wire limit required"); }
        org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope.safe(protectionVersion);
        this.maxWireBytes = maxWireBytes; this.protectionVersion = protectionVersion;
    }

    public CaptureReceipt prepare(Claim claim, Instant payloadRecoverUntil) {
        return prepare(claim, payloadRecoverUntil, null, () -> {});
    }

    CaptureReceipt prepare(Claim claim, Instant payloadRecoverUntil, GitHubMachineCapture.Run expectedRun,
            Runnable recheckCaller) {
        if (claim == null || payloadRecoverUntil == null || !payloadRecoverUntil.isAfter(clock.instant())) {
            throw new CaptureFailure(INVALID_INPUT);
        }
        var receipt = capture.claimPreparation(claim);
        receipt.requireClaim(claim.owner(), receipt.claimFence(), clock.instant());
        var request = new GitHubMachineCapture.Request(receipt.scope(), receipt.runId(), receipt.runAttempt(), receipt.correlationId());
        GitHubMachineCapture.StepLogs source;
        try { source = github.readStepLogs(request); }
        catch (CaptureFailure failure) {
            if (failure.reason() == SOURCE_EXPIRED) { capture.expire(receipt.id()); }
            throw failure;
        }
        catch (RuntimeException unavailable) { throw new CaptureFailure(UNAVAILABLE); }
        receipt.requireClaim(claim.owner(), receipt.claimFence(), clock.instant());
        if (source == null || source.run() == null || !request.equals(source.run().request())) {
            throw new CaptureFailure(CONFLICT);
        }
        if (expectedRun != null) {
            var actual = source.run();
            var bound = new GitHubMachineCapture.Run(expectedRun.request(), actual.fullName(), actual.branch(),
                    actual.commitSha(), actual.status(), actual.conclusion(), actual.createdAt(),
                    actual.updatedAt(), actual.startedAt(), actual.jobs());
            if (!expectedRun.equals(bound)) throw new CaptureFailure(CONFLICT);
        }
        var classified = profile.classify(source);
        var observation = classified.observation();
        var result = normalizer.handle(new NormalizeObservationCommand(observation, receipt.scope().context(), receipt.scope().pipeline(),
                receipt.snapshotMessageId(), observation.evidence() == null ? null : receipt.evidenceMessageId()));
        for (var output : encoder.encode(result)) {
            if (output.body().length > maxWireBytes) { throw new CaptureFailure(INVALID_INPUT); }
        }
        receipt.requireClaim(claim.owner(), receipt.claimFence(), clock.instant());
        recheckCaller.run();
        return capture.prepare(new Prepare(receipt.id(), claim.owner(), receipt.claimFence(), result,
                FailedRunEvidenceProfile.VERSION, protectionVersion, classified.fragmentLocator(), payloadRecoverUntil));
    }
}
