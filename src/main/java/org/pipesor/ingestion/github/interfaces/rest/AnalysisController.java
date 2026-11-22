package org.pipesor.ingestion.github.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.pipesor.ingestion.capture.application.commandhandlers.GitHubAnalysisHandler;
import org.pipesor.ingestion.configuration.ApiProperties;
import org.pipesor.ingestion.github.interfaces.rest.resources.GitHubResources;
import org.pipesor.ingestion.shared.interfaces.rest.ApiFailure;
import org.pipesor.ingestion.shared.interfaces.rest.RequestContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** On-demand analysis of a selected failed run attempt (MVP HTTP API v1, generalized from the pilot route). */
@RestController
@SecurityRequirement(name = "platformBearer")
public final class AnalysisController {
    private static final String PATH = "/api/v1/github/repositories/{repositoryId}/workflows/{workflowId}/runs/{runId}/attempts/{attempt}/analysis";
    private final GitHubAnalysisHandler analyses;
    private final ApiProperties properties;
    private final Clock clock;

    public AnalysisController(GitHubAnalysisHandler analyses, ApiProperties properties, Clock clock) {
        this.analyses = analyses; this.properties = properties; this.clock = clock;
    }

    @PostMapping(PATH)
    @Operation(summary = "Request analysis of a failed run attempt", description = "Requires ADR-020 OPERATE conditions on an admitted repository and workflow. Records durable intent only; preparation and publication are asynchronous.")
    public ResponseEntity<GitHubResources.Analysis> analyze(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token, HttpServletRequest request,
            @PathVariable String repositoryId, @PathVariable String workflowId, @PathVariable String runId, @PathVariable int attempt) {
        var context = RequestContext.self(token, request, properties.limits(), clock);
        var result = analyses.request(context.access(), target(repositoryId, workflowId, runId, attempt), context.startedAt(),
                () -> context.requireLive(clock));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(resource(result));
    }

    @GetMapping(PATH)
    @Operation(summary = "Read the analysis status of a run attempt", description = "Same authorization as the request. executionId and incidentId are Pipeline's deterministic identifiers, present once published.")
    public ResponseEntity<GitHubResources.Analysis> status(@Parameter(hidden = true) @AuthenticationPrincipal Jwt token, HttpServletRequest request,
            @PathVariable String repositoryId, @PathVariable String workflowId, @PathVariable String runId, @PathVariable int attempt) {
        var context = RequestContext.self(token, request, properties.limits(), clock);
        var result = analyses.status(context.access(), target(repositoryId, workflowId, runId, attempt), context.startedAt(),
                () -> context.requireLive(clock)).orElseThrow(() -> new ApiFailure(404, "ANALYSIS_NOT_FOUND"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resource(result));
    }

    private static GitHubAnalysisHandler.Target target(String repositoryId, String workflowId, String runId, int attempt) {
        try { return new GitHubAnalysisHandler.Target(repositoryId, workflowId, runId, attempt); }
        catch (RuntimeException invalid) { throw new ApiFailure(400, "INVALID_REQUEST"); }
    }

    private static GitHubResources.Analysis resource(GitHubAnalysisHandler.Analysis analysis) {
        return new GitHubResources.Analysis(analysis.analysisId(), analysis.phase(), analysis.blockedReason(),
                analysis.executionId(), analysis.incidentId());
    }
}
