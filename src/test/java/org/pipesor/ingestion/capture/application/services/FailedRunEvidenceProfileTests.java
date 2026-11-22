package org.pipesor.ingestion.capture.application.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.*;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.SourceLocator;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.StageName;
import static org.junit.jupiter.api.Assertions.*;

class FailedRunEvidenceProfileTests {
    static final Instant START = Instant.parse("2026-11-10T12:00:00Z");
    final FailedRunEvidenceProfile profile = new FailedRunEvidenceProfile(1_000_000, 32_768);

    @Test void successRecordsStagesWithoutEvidence() {
        var build = new Step(1, "Set up job", "completed", "success", START, START.plusSeconds(1));
        var test = new Step(2, "Run unit tests", "completed", "success", START.plusSeconds(1), START.plusSeconds(9));
        var result = profile.classify(new StepLogs(run("success", job("success", build, test)), List.of()));
        assertNull(result.observation().evidence());
        assertNull(result.observation().failure());
        assertNull(result.fragmentLocator());
        assertTrue(result.observation().stages().stream().anyMatch(s -> s.stage() == StageName.TESTING));
        assertTrue(result.observation().stages().stream().anyMatch(s -> s.stage() == StageName.INFRASTRUCTURE));
    }

    @Test void failedTestStepUsesItsOwnMemberAndEndsAtTheSpecificError() throws Exception {
        var setup = new Step(1, "Set up job", "completed", "success", START, START.plusSeconds(1));
        var tests = new Step(3, "Run mvn test", "completed", "failure", START.plusSeconds(2), START.plusSeconds(20));
        String log = line(2, "[INFO] Running OrderServiceTest")
                + line(3, "[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0")
                + line(4, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project shop")
                + line(5, "##[error]Process completed with exit code 1.");
        var job = job("failure", setup, tests);
        var result = profile.classify(new StepLogs(run("failure", job), List.of(new StepLog(job.id(), 3, bytes(log)))));
        var evidence = result.observation().evidence();
        assertEquals(StageName.TESTING, evidence.stage());
        assertEquals("Test step reported a failure", result.observation().failure().summary());
        String expected = line(2, "[INFO] Running OrderServiceTest") + line(3, "[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0")
                + line(4, "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project shop");
        assertEquals(expected, evidence.excerpt());
        assertEquals("v2:job:991002:member:build%2F3_Run%20mvn%20test.txt:lines:1-3:sha256:" + sha(expected), result.fragmentLocator());
        assertEquals(result.fragmentLocator(), evidence.sourceLocator());
        assertTrue(SourceLocator.valid(result.fragmentLocator(), "991002"));
        assertEquals(START.plusSeconds(4), result.observation().failure().detectedAt());
    }

    @Test void setupFailureUsesTheAggregateMemberInsideTheStepWindow() throws Exception {
        var setup = new Step(1, "Set up job", "completed", "failure", START.plusSeconds(1), START.plusSeconds(3));
        var job = job("failure", setup);
        String raw = "﻿" + line(0, "Requested labels: ubuntu-latest")
                + line(2, "##[error]Unable to resolve action actions/checkout@deadbeef, unable to find version")
                + line(9, "Cleaning up orphan processes");
        var result = profile.classify(new StepLogs(run("failure", job), List.of(),
                List.of(new AggregateLog(job.id(), "0_build.txt", bytes(raw)))));
        assertEquals(StageName.INFRASTRUCTURE, result.observation().failure().stage());
        String selected = line(2, "##[error]Unable to resolve action actions/checkout@deadbeef, unable to find version");
        assertEquals(selected, result.observation().evidence().excerpt());
        assertEquals("v2:job:991002:member:0_build.txt:lines:2-2:sha256:" + sha(selected), result.fragmentLocator());
    }

    @Test void digestKeepsTheOriginalBomWhileTheExcerptDropsIt() throws Exception {
        var setup = new Step(1, "Set up job", "completed", "failure", START, START.plusSeconds(3));
        var job = job("failure", setup);
        String raw = "\uFEFF" + line(0, "Current runner version: '2.329.0'") + line(2, "##[error]Unable to resolve action x");
        var result = profile.classify(new StepLogs(run("failure", job), List.of(),
                List.of(new AggregateLog(job.id(), "0_build.txt", bytes(raw)))));
        assertFalse(result.observation().evidence().excerpt().startsWith("\uFEFF"));
        assertEquals(raw.substring(1), result.observation().evidence().excerpt());
        assertTrue(result.fragmentLocator().endsWith(":lines:1-2:sha256:" + sha(raw)));
    }

    @Test void keepsTheExceptionSummaryThatFollowsTheAnnotatedLine() throws Exception {
        var step = new Step(7, "Build and check the candidate image", "completed", "failure", START, START.plusSeconds(20));
        String log = line(1, "#10 DONE 2.4s") + line(2, "Traceback (most recent call last):")
                + line(2, "  File \"tools/check_image.py\", line 32, in <module>")
                + line(2, "    check_image(sys.argv[1], os.environ['GITHUB_SHA'])")
                + line(2, "##[error]    raise RuntimeError('Packaged service did not reject missing runtime configuration as expected')")
                + line(2, "RuntimeError: Packaged service did not reject missing runtime configuration as expected")
                + line(3, "##[error]Process completed with exit code 1.");
        var job = job("failure", step);
        var result = profile.classify(new StepLogs(run("failure", job), List.of(new StepLog(job.id(), 7, bytes(log)))));
        var excerpt = result.observation().evidence().excerpt();
        assertTrue(excerpt.contains("RuntimeError: Packaged service did not reject"), "the exception summary reaches the model");
        assertFalse(excerpt.contains("Process completed with exit code"));
        assertTrue(result.fragmentLocator().contains(":lines:1-6:"));
    }

    @Test void containerFailureFallsBackToTheGenericExitLine() throws Exception {
        var image = new Step(4, "Build Docker image", "completed", "failure", START, START.plusSeconds(5));
        String log = line(1, "#5 [2/4] RUN apt-get update") + line(2, "#5 exit code: 100")
                + line(3, "##[error]Process completed with exit code 1.");
        var job = job("failure", image);
        var result = profile.classify(new StepLogs(run("failure", job), List.of(new StepLog(job.id(), 4, bytes(log)))));
        assertEquals(StageName.CONTAINER, result.observation().failure().stage());
        assertEquals(log, result.observation().evidence().excerpt());
        assertTrue(result.fragmentLocator().contains(":lines:1-3:"));
    }

    @Test void excerptIsBoundedInLinesAndBytes() {
        var step = new Step(2, "Compile", "completed", "failure", START, START.plusSeconds(100));
        var log = new StringBuilder();
        for (int i = 0; i < 90; i++) log.append(line(1, "compiling unit " + i));
        log.append(line(2, "ERROR: cannot find symbol"));
        var job = job("failure", step);
        var result = profile.classify(new StepLogs(run("failure", job), List.of(new StepLog(job.id(), 2, bytes(log.toString())))));
        assertTrue(result.fragmentLocator().contains(":lines:52-91:"));
        var tight = new FailedRunEvidenceProfile(1_000_000, 200);
        var bounded = tight.classify(new StepLogs(run("failure", job), List.of(new StepLog(job.id(), 2, bytes(log.toString())))));
        assertTrue(bounded.observation().evidence().excerpt().getBytes(StandardCharsets.UTF_8).length <= 200);
        assertTrue(bounded.fragmentLocator().contains("-91:"));
    }

    @Test void refusesMissingSourcesOversizedInputAndInvalidText() {
        var step = new Step(2, "Compile", "completed", "failure", START, START.plusSeconds(5));
        var job = job("failure", step);
        assertThrows(CaptureFailure.class, () -> profile.classify(new StepLogs(run("failure", job), List.of())));
        assertThrows(CaptureFailure.class, () -> new FailedRunEvidenceProfile(10, 100)
                .classify(new StepLogs(run("failure", job), List.of(new StepLog(job.id(), 2, bytes(line(1, "ERROR: x")))))));
        assertThrows(CaptureFailure.class, () -> profile.classify(new StepLogs(run("failure", job),
                List.of(new StepLog(job.id(), 2, new byte[]{(byte) 0xff, (byte) 0xfe})))));
        assertThrows(CaptureFailure.class, () -> profile.classify(new StepLogs(run("in_progress", job), List.of())));
    }

    @Test void privateValuesStayOutOfStringsAndSummaries() {
        var step = new Step(2, "Deploy to production", "completed", "failure", START, START.plusSeconds(5));
        var job = job("failure", step);
        var result = profile.classify(new StepLogs(run("failure", job),
                List.of(new StepLog(job.id(), 2, bytes(line(1, "Error: token=secret-private-value rejected"))))));
        assertEquals(StageName.DEPLOYMENT, result.observation().failure().stage());
        assertFalse(result.toString().contains("secret"));
        assertFalse(result.observation().failure().summary().contains("secret"));
    }

    static Job job(String conclusion, Step... steps) {
        return new Job("991002", "build", "completed", conclusion, START, START.plusSeconds(30), List.of(steps));
    }

    static Run run(String conclusion, Job job) {
        var scope = new CaptureScope("pilot", UUID.fromString("12121212-1212-4212-8212-121212121212"), "101", "202", "303", "500", "600", "rev2");
        var request = new Request(scope, "98765432100000001", 1, "corr-profile-1");
        String status = conclusion.equals("in_progress") ? "in_progress" : "completed";
        return new Run(request, "example/shop", "main", "b".repeat(64), status, conclusion.equals("in_progress") ? null : conclusion,
                START.minusSeconds(5), START.plusSeconds(40), START, List.of(job));
    }

    static String line(int seconds, String text) { return START.plusSeconds(seconds) + " " + text + "\n"; }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(text)));
    }
}
