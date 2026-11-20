package org.pipesor.ingestion.capture.application.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.StageName;
import static org.junit.jupiter.api.Assertions.*;

class VerifyPreparationProfileTests {
    static final Instant START = Instant.parse("2026-11-10T12:00:00Z");
    static final String BUILD = line(1, "[INFO] --- compiler:3.14.1:compile (default-compile) @ ingestion ---");
    static final String TEST = line(2, "[INFO] --- surefire:3.5.4:test (default-test) @ ingestion ---");
    static final String FAILURE = line(4, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project ingestion: secret-private-error");
    final VerifyPreparationProfile profile = new VerifyPreparationProfile(32768);

    @Test void successUsesSourceTimeAndObservedEnvelopesWithoutInventingCompletion() {
        String maven = BUILD + TEST + line(3, "[INFO] Tests complete")
                + line(4, "[INFO] --- jar:3.4.2:jar (default-jar) @ ingestion ---") + line(5, "[INFO] Packaged");
        var classified = profile.classify(source("success", maven, line(7, "image built")));
        var observation = classified.observation();
        assertEquals(START.plusSeconds(12), observation.occurredAt());
        assertEquals(observation.occurredAt(), observation.sourceUpdatedAt());
        assertEquals(START, observation.execution().startedAt());
        assertNull(observation.execution().completedAt());
        assertNull(observation.evidence()); assertNull(classified.fragmentLocator());
        assertEquals("corr-profile-2", observation.correlationId());
        assertEquals("b".repeat(64), observation.commitSha());
        var build = observation.stages().stream().filter(s -> s.stage() == StageName.BUILD).findFirst().orElseThrow();
        assertEquals(START.plusSeconds(1), build.startedAt());
        assertEquals(START.plusSeconds(6), build.completedAt());
        var testing = observation.stages().stream().filter(s -> s.stage() == StageName.TESTING).findFirst().orElseThrow();
        assertEquals(START.plusSeconds(4), testing.completedAt());
    }
    @Test void failedTestingKeepsCompletePhaseAndActualLocatorPrivate() {
        String phase = TEST + line(3, "secret-token=synthetic-do-not-publish") + FAILURE + line(5, "[ERROR] More diagnostic details");
        var result = profile.classify(source("failure", BUILD + phase, null));
        assertEquals(StageName.TESTING, result.observation().failure().stage());
        assertEquals(START.plusSeconds(4), result.observation().failure().detectedAt());
        assertEquals(phase, result.observation().evidence().excerpt());
        assertEquals("v1:job:991002:step:4:index:1", result.fragmentLocator());
        assertEquals(1L, result.observation().evidence().sequence());
        assertFalse(result.toString().contains("secret"));
        assertFalse(result.observation().failure().summary().contains("secret"));
    }
    @Test void imageFailureUsesActualStepAndEntireExcerpt() {
        String image = line(7, "#1 build started") + line(8, "sensitive-email@example.test") + line(9, "process failed");
        var result = profile.classify(source("image-failure", BUILD + TEST + line(3, "Tests complete"), image));
        assertEquals(StageName.CONTAINER, result.observation().failure().stage());
        assertEquals(image, result.observation().evidence().excerpt());
        assertEquals("v1:job:991002:step:5:index:0", result.fragmentLocator());
        assertEquals(START.plusSeconds(10), result.observation().failure().detectedAt());
    }
    @Test void refusesUnknownPluginAndContradictoryTerminalGoal() {
        refused(source("success", BUILD + line(2, "[INFO] --- exec:3.5.0:exec (default) @ ingestion ---"), line(7, "built")));
        refused(source("failure", BUILD + TEST + FAILURE.replace("maven-surefire-plugin", "maven-compiler-plugin"), null));
    }
    @Test void refusesMultipleFailuresAndUnsupportedConclusionsWithoutLeakingEvidence() {
        refused(source("failure", BUILD + TEST + FAILURE + FAILURE.replace("12:00:04", "12:00:05"), null));
        String preamble = FAILURE.replace("12:00:04", "12:00:00");
        refused(source("failure", preamble + BUILD + TEST + FAILURE, null));
        refused(source("success", preamble + BUILD + TEST, line(7, "built")));
        var original = source("success", BUILD + TEST, line(7, "built"));
        Run r = original.run();
        var unsupported = new Run(r.request(), r.fullName(), r.branch(), r.commitSha(), "completed", "cancelled", r.createdAt(), r.updatedAt(), r.startedAt(), r.jobs());
        refused(new StepLogs(unsupported, original.logs()));
    }
    @Test void refusesScopeOrJobAmbiguityAndDuplicateOrCrossedStepLogs() {
        var source = source("success", BUILD + TEST, line(7, "built"));
        Run r = source.run();
        var wrongScope = new CaptureScope("pilot", r.request().scope().tenantId(), "101", "202", "303", "1446318021", "412504984", "rev2");
        var request = new Request(wrongScope, r.request().runId(), 2, r.request().correlationId());
        refused(new StepLogs(new Run(request, r.fullName(), r.branch(), r.commitSha(), r.status(), r.conclusion(), r.createdAt(), r.updatedAt(), r.startedAt(), r.jobs()), source.logs()));
        refused(new StepLogs(new Run(r.request(), r.fullName(), r.branch(), r.commitSha(), r.status(), r.conclusion(), r.createdAt(), r.updatedAt(), r.startedAt(), List.of(r.jobs().getFirst(), r.jobs().getFirst())), source.logs()));
        refused(new StepLogs(r, List.of(source.logs().getFirst(), source.logs().getFirst())));
        refused(new StepLogs(r, List.of(new StepLog("991003", 4, BUILD.getBytes(StandardCharsets.UTF_8)))));
    }
    @Test void refusesOversizedAndInvalidUtf8RatherThanTruncating() {
        var source = source("failure", BUILD + TEST + FAILURE, null);
        var failure = assertThrows(CaptureFailure.class, () -> new VerifyPreparationProfile(20).classify(source));
        assertEquals(CaptureFailure.Reason.INVALID_INPUT, failure.reason());
        refused(new StepLogs(source.run(), List.of(new StepLog("991002", 4, new byte[] {(byte) 0xc3, 0x28}))));
    }
    @Test void refusesMissingProofAndNonMonotonicTimestamps() {
        refused(source("failure", BUILD + TEST + line(3, "[ERROR] unrelated test output"), null));
        refused(source("success", TEST + BUILD, line(7, "built")));
        refused(source("success", BUILD + TEST, "untimestamped body"));
    }
    @Test void compileFailureBelongsToBuildAndUsesItsOwnSegment() {
        String phase = BUILD + line(2, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile (default-compile) on project ingestion: failed");
        var result = profile.classify(source("failure", phase, null));
        assertEquals(StageName.BUILD, result.observation().failure().stage());
        assertEquals(phase, result.observation().evidence().excerpt());
        assertEquals("v1:job:991002:step:4:index:0", result.fragmentLocator());
    }

    @Test void bindsOriginalAggregateLineIncludingItsTerminatorAndRejectsAmbiguity() throws Exception {
        var run = source("failure", BUILD, null).run();
        var raw = FAILURE.replace("\n", "\r\n");
        var member = new AggregateLog("991002", "0_verify.txt", (BUILD + raw).getBytes(StandardCharsets.UTF_8));
        var result = profile.classify(new StepLogs(run, List.of(), List.of(member)));
        var hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        assertEquals("v2:job:991002:member:0_verify.txt:lines:2-2:sha256:" + java.util.HexFormat.of().formatHex(hash),
                result.fragmentLocator());
        assertEquals(raw, result.observation().evidence().excerpt());
        assertNull(result.observation().evidence().sequence());
        for (var invalid : List.of(new AggregateLog("991003", member.member(), member.bytes()),
                new AggregateLog(member.jobId(), "other.txt", member.bytes()),
                new AggregateLog(member.jobId(), member.member(), (raw + raw).getBytes(StandardCharsets.UTF_8)))) {
            refused(new StepLogs(run, List.of(), List.of(invalid)));
        }
    }

    @Test void setupErrorProducesOnlyAnInfrastructureObservation() {
        var base = source("failure", BUILD, null).run();
        var step = new Step(1, "Set up job", "completed", "failure", START, START.plusSeconds(5));
        var job = new Job("991002", "verify", "completed", "failure", START, START.plusSeconds(6), List.of(step));
        var run = new Run(base.request(), base.fullName(), base.branch(), base.commitSha(), base.status(),
                base.conclusion(), base.createdAt(), base.updatedAt(), base.startedAt(), List.of(job));
        var raw = "\uFEFF" + line(2, "##[error]Synthetic setup symptom");
        var member = new AggregateLog(job.id(), "0_verify.txt", raw.getBytes(StandardCharsets.UTF_8));
        var result = profile.classify(new StepLogs(run, List.of(), List.of(member)));
        assertEquals("Job setup reported an error", result.observation().failure().summary());
        assertEquals(StageName.INFRASTRUCTURE, result.observation().failure().stage());
        assertEquals(raw, result.observation().evidence().excerpt());
        for (var insufficient : List.of(line(2, "Starting job"), line(8, "##[error]Late line"))) {
            refused(new StepLogs(run, List.of(), List.of(new AggregateLog(job.id(), member.member(),
                    insufficient.getBytes(StandardCharsets.UTF_8)))));
        }
    }

    private void refused(StepLogs source) {
        var failure = assertThrows(CaptureFailure.class, () -> profile.classify(source));
        assertEquals(CaptureFailure.Reason.INVALID_INPUT, failure.reason());
        assertEquals("INVALID_INPUT", failure.getMessage()); assertNull(failure.getCause());
    }
    static String line(int seconds, String text) { return START.plusSeconds(seconds) + " " + text + "\n"; }
    static StepLogs source(String outcome, String maven, String image) {
        String conclusion = outcome.equals("image-failure") ? "failure" : outcome;
        var scope = new CaptureScope("pilot", UUID.fromString("12121212-1212-4212-8212-121212121212"), "101", "202", "303", "1446318020", "412504984", "rev2");
        var request = new Request(scope, "98765432100000001", 2, "corr-profile-2");
        var mavenStep = new Step(4, "Compile, test with isolated PostgreSQL and package", "completed", outcome.equals("failure") ? "failure" : "success", START, START.plusSeconds(6));
        var imageStep = new Step(5, "Build local non-root image", "completed", outcome.equals("failure") ? "skipped" : outcome.equals("image-failure") ? "failure" : "success", START.plusSeconds(6), START.plusSeconds(10));
        var job = new Job("991002", "verify", "completed", conclusion, START, START.plusSeconds(10), List.of(mavenStep, imageStep));
        var run = new Run(request, "pipesor-org/pipesor-ingestion-normalization-service", "feature/source-distinct", "b".repeat(64), "completed", conclusion, START.minusSeconds(5), START.plusSeconds(12), START, List.of(job));
        var logs = new java.util.ArrayList<StepLog>();
        logs.add(new StepLog(job.id(), 4, maven.getBytes(StandardCharsets.UTF_8)));
        if (image != null) { logs.add(new StepLog(job.id(), 5, image.getBytes(StandardCharsets.UTF_8))); }
        return new StepLogs(run, logs);
    }
}
