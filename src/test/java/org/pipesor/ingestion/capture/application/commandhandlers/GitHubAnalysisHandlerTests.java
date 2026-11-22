package org.pipesor.ingestion.capture.application.commandhandlers;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.ports.MachineScopes;
import org.pipesor.ingestion.capture.domain.model.aggregates.CaptureReceipt;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.domain.services.PipelineIds;
import org.pipesor.ingestion.github.application.queries.GitHubViews;
import org.pipesor.ingestion.github.application.queries.VerifyAccessQuery;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Path-selected analysis keeps the pilot's OPERATE authorization and maps ledger phases for the dashboard. */
class GitHubAnalysisHandlerTests {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final CaptureScope SCOPE = new CaptureScope("pilot", TENANT, "120", "240", "360", "480", "590", "r1");
    private static final String SUBJECT = TENANT + ":" + UUID.randomUUID();
    private static final AccessContext DISCOVER = new AccessContext(TENANT.toString(), SUBJECT, SUBJECT, AccessOperation.DISCOVER, "corr");
    private static final GitHubAnalysisHandler.Target TARGET = new GitHubAnalysisHandler.Target("480", "590", "7001", 2);
    private final MachineScopes scopes = mock(MachineScopes.class);
    private final RequestGitHubCapture capture = mock(RequestGitHubCapture.class);
    private final GitHubQueries github = mock(GitHubQueries.class);
    private final GitHubAnalysisHandler handler = new GitHubAnalysisHandler(scopes, capture, github, "pilot", TENANT, Clock.systemUTC());

    private void access(boolean administrator, String account) {
        doAnswer(invocation -> {
            var query = invocation.getArgument(0, VerifyAccessQuery.class);
            assertEquals(AccessOperation.OPERATE, query.context().operation());
            var link = new GitHubLink(TENANT.toString(), SUBJECT, "42", "1", Instant.EPOCH, UUID.randomUUID());
            return new GitHubViews.Access(query.context(), link, Map.of(new RepositoryTarget("480", "360"),
                    new GitHubViews.RepositoryAccess(true, true, administrator, account)), Instant.now());
        }).when(github).verifyAccess(any());
    }

    private static CaptureReceipt receipt(String phase) {
        return new CaptureReceipt(UUID.randomUUID(), SCOPE, "7001", 2, "corr", UUID.randomUUID(), UUID.randomUUID(), phase, null, null, 0, null);
    }

    @Test void recordsIntentOnlyForAnAdministratorOfTheAdmittedScope() {
        when(scopes.completedScope("pilot", TENANT, "480", "590")).thenReturn(Optional.of(SCOPE));
        when(scopes.completedReplayUntil(SCOPE)).thenReturn(Instant.now().plusSeconds(600));
        when(capture.handle(eq(SCOPE), eq("7001"), eq(2), any(), any())).thenReturn(receipt("ACCEPTED"));
        access(true, "240");
        var started = Instant.now().minusSeconds(1);
        var result = handler.request(DISCOVER, TARGET, started, () -> {});
        assertEquals("ACCEPTED", result.phase());
        assertNull(result.executionId());

        access(false, "240");
        assertThrows(GitHubAnalysisHandler.AnalysisDenied.class, () -> handler.request(DISCOVER, TARGET, started, () -> {}));
        access(true, "999");
        assertThrows(GitHubAnalysisHandler.AnalysisDenied.class, () -> handler.request(DISCOVER, TARGET, started, () -> {}));
        when(scopes.completedScope("pilot", TENANT, "480", "591")).thenReturn(Optional.empty());
        // ADR-023: a workflow without an ACTIVE capture scope is pending, not denied.
        assertThrows(GitHubAnalysisHandler.AnalysisPending.class, () -> handler.request(DISCOVER,
                new GitHubAnalysisHandler.Target("480", "591", "7001", 2), started, () -> {}));
        verify(capture, times(1)).handle(any(), any(), anyInt(), any(), any());
    }

    @Test void publishedAnalysesExposePipelineDeterministicIdentifiers() {
        when(scopes.completedScope("pilot", TENANT, "480", "590")).thenReturn(Optional.of(SCOPE));
        access(true, "240");
        var failed = receipt("PUBLISHED");
        when(capture.find(SCOPE, "7001", 2)).thenReturn(Optional.of(failed));
        when(capture.observedFailure(failed)).thenReturn(true);
        var published = handler.status(DISCOVER, TARGET, Instant.now().minusSeconds(1), () -> {}).orElseThrow();
        var execution = PipelineIds.execution("480", "7001", 2);
        assertEquals(execution, published.executionId());
        assertEquals(PipelineIds.incident(execution), published.incidentId());
        // A successful run is only an execution (a verification candidate): it opens no incident.
        var succeeded = receipt("PUBLISHED");
        when(capture.find(SCOPE, "7001", 2)).thenReturn(Optional.of(succeeded));
        when(capture.observedFailure(succeeded)).thenReturn(false);
        var verification = handler.status(DISCOVER, TARGET, Instant.now().minusSeconds(1), () -> {}).orElseThrow();
        assertEquals(execution, verification.executionId());
        assertNull(verification.incidentId());
        when(capture.find(SCOPE, "7001", 2)).thenReturn(Optional.of(receipt("PREPARED")));
        assertEquals("PREPARING", handler.status(DISCOVER, TARGET, Instant.now().minusSeconds(1), () -> {}).orElseThrow().phase());
        when(capture.find(SCOPE, "7001", 2)).thenReturn(Optional.of(receipt("BLOCKED")));
        var blocked = handler.status(DISCOVER, TARGET, Instant.now().minusSeconds(1), () -> {}).orElseThrow();
        assertEquals("BLOCKED", blocked.phase());
        assertEquals("SCOPE_CHANGED", blocked.blockedReason());
    }
}
