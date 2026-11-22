package org.pipesor.ingestion.capture.application.commandhandlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** On-demand capture admits failed runs (analysis) and successful runs (verification), nothing else. */
class RequestGitHubCaptureTests {
    private static final CaptureScope SCOPE = new CaptureScope("pilot", UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "120", "240", "360", "480", "590", "r1");
    private final CaptureHandler capture = mock(CaptureHandler.class);
    private final GitHubMachineCapture github = mock(GitHubMachineCapture.class);
    private final RequestGitHubCapture request = new RequestGitHubCapture(capture, github);

    private void run(String status, String conclusion) {
        when(github.readRun(any())).thenAnswer(invocation -> new GitHubMachineCapture.Run(invocation.getArgument(0),
                "owner/repository", "main", "abc", status, conclusion, Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                Instant.EPOCH, List.of()));
    }

    @ParameterizedTest @ValueSource(strings = {"failure", "timed_out", "success"})
    void admitsFailedRunsForAnalysisAndSuccessfulRunsForVerification(String conclusion) {
        run("completed", conclusion);
        var receipt = mock(CaptureReceipt.class);
        when(capture.accept(any())).thenReturn(receipt);
        int[] rechecks = {0};
        assertSame(receipt, request.handle(SCOPE, "7001", 1, Instant.EPOCH.plusSeconds(600), () -> rechecks[0]++));
        assertEquals(1, rechecks[0]);
    }

    @ParameterizedTest @ValueSource(strings = {"cancelled", "skipped", "neutral"})
    void rejectsOtherConclusionsWithoutRecordingIntent(String conclusion) {
        run("completed", conclusion);
        var failure = assertThrows(CaptureFailure.class, () -> request.handle(SCOPE, "7001", 1, Instant.EPOCH, () -> {}));
        assertEquals(CaptureFailure.Reason.INVALID_INPUT, failure.reason());
        verify(capture, never()).accept(any());
    }

    @Test void rejectsRunsThatAreNotCompleted() {
        run("in_progress", null);
        assertThrows(CaptureFailure.class, () -> request.handle(SCOPE, "7001", 1, Instant.EPOCH, () -> {}));
        verify(capture, never()).accept(any());
    }
}
