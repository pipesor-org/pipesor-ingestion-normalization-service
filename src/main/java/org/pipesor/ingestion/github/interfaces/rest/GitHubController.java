package org.pipesor.ingestion.github.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.*;
import org.pipesor.ingestion.configuration.ApiProperties;
import org.pipesor.ingestion.github.application.commands.ConnectionCommands;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.queries.*;
import org.pipesor.ingestion.github.application.services.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.github.interfaces.rest.resources.GitHubResources;
import org.pipesor.ingestion.shared.interfaces.rest.*;
import org.pipesor.ingestion.shared.security.SubjectTokenFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@SecurityRequirement(name = "platformBearer")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid or unsupported request", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
        @ApiResponse(responseCode = "401", description = "Missing or invalid platform token", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
        @ApiResponse(responseCode = "403", description = "Caller or disclosure denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
        @ApiResponse(responseCode = "409", description = "Connection required or revision conflict", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
        @ApiResponse(responseCode = "503", description = "Dependency, capacity or deadline unavailable", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class)))
})
public class GitHubController {
    private static final int DEFAULT_RUN_PAGE_SIZE = 24;
    private final GitHubQueries queries;
    private final GitHubRepositorySelection selections;
    private final GitHubConnectionQueries connections;
    private final GitHubConnections commands;
    private final ApiProperties.Limits limits;
    private final int maxRunPages;
    private final Clock clock;

    public GitHubController(GitHubQueries queries, GitHubRepositorySelection selections,
                             GitHubConnectionQueries connections, GitHubConnections commands,
                             ApiProperties properties,
                             @org.springframework.beans.factory.annotation.Value("${pipesor.github.limits.max-run-pages:10}") int maxRunPages, Clock clock) {
        this.queries = queries; this.selections = selections; this.connections = connections; this.commands = commands;
        this.limits = properties.limits(); this.maxRunPages = maxRunPages; this.clock = clock;
    }

    @PostMapping("/internal/v1/github/access-verifications")
    @Parameter(name = "X-Correlation-Id", in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, required = true, example = "corr-http")
    @Parameter(name = SubjectTokenFilter.HEADER, in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, required = true, description = "The user's unmodified session token (platform identity contract v1, section 4)")
    @Operation(summary = "Verify current GitHub facts for the exact repository selection", description = "Pipeline workload token with Pipeline.GitHubFacts.Read plus the subject's session token. X-Correlation-Id must match correlationId. Deadline is bounded by the server request budget and both token expiries. Recipient is a same-tenant object ID for GRANT operations only.")
    public GitHubResources.AccessVerification verify(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                       @RequestBody GitHubResources.AccessRequest resource) {
        var context = RequestContext.internal(SubjectTokenFilter.subject(request), jwt, request, resource.requestId(), resource.correlationId(), resource.deadline(),
                resource.operation(), resource.recipientObjectId(), limits, clock);
        var ids = ids(resource.repositoryIds());
        var targets = resolve(context, ids);
        var result = queries.verifyAccess(new VerifyAccessQuery(context.access(), resolved(targets)));
        return access(context, ids, targets, result);
    }

    @PostMapping("/internal/v1/github/workflow-verifications")
    @Parameter(name = "X-Correlation-Id", in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, required = true, example = "corr-http")
    @Parameter(name = SubjectTokenFilter.HEADER, in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER, required = true, description = "The user's unmodified session token (platform identity contract v1, section 4)")
    @Operation(summary = "Verify selected REGISTER workflow metadata", description = "Pipeline workload token plus the subject's session token; no registration, capability or grant decision.")
    public GitHubResources.WorkflowVerification workflow(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                           @RequestBody GitHubResources.WorkflowRequest resource) {
        if (resource.operation() != AccessOperation.REGISTER) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        var context = RequestContext.internal(SubjectTokenFilter.subject(request), jwt, request, resource.requestId(), resource.correlationId(), resource.deadline(),
                AccessOperation.REGISTER, null, limits, clock);
        var ids = ids(Collections.singletonList(resource.repositoryId()));
        id(resource.workflowId());
        var targets = resolve(context, ids);
        var target = targets.get(resource.repositoryId()).orElseThrow(() -> new ApiFailure(403, "DENIED"));
        var result = queries.selectWorkflow(new SelectWorkflowQuery(context.access(), target, resource.workflowId()));
        if (result == null || result.repository() == null || result.workflow() == null
                || !target.equals(result.repository().target()) || !target.equals(result.workflow().repository())
                || !resource.workflowId().equals(result.workflow().workflowId())) { throw ApiFailure.unavailable(); }
        var access = access(context, ids, targets, result.access());
        return new GitHubResources.WorkflowVerification(access, target.repositoryId(), result.workflow().workflowId(),
                result.repository().fullName(), result.repository().defaultBranch(), result.workflow().name(), result.workflow().file());
    }

    @GetMapping("/api/v1/github/repositories")
    @Operation(summary = "Discover a complete bounded first page for the authenticated user", description = "Cursor continuation is unavailable until its binding protocol is accepted. A result exceeding limit returns 503; results are never silently truncated.")
    public GitHubResources.Repositories repositories(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                       @RequestParam int limit, @RequestParam(required = false) String cursor) {
        page(limit, cursor);
        var context = RequestContext.self(jwt, request, limits, clock);
        var result = queries.discoverRepositories(new DiscoverRepositoriesQuery(context.access()));
        if (result == null || result.repositories().size() > limit) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        return new GitHubResources.Repositories(discoveryContext(context, result.link(), result.observedAt()),
                result.repositories().stream().map(GitHubController::repository).toList());
    }

    @GetMapping("/api/v1/github/repositories/{repositoryId}/workflows")
    @Operation(summary = "Discover a complete bounded first page of current workflows for self")
    public GitHubResources.Workflows workflows(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                 @PathVariable String repositoryId, @RequestParam int limit,
                                                 @RequestParam(required = false) String cursor) {
        page(limit, cursor);
        var context = RequestContext.self(jwt, request, limits, clock);
        var targets = resolve(context, ids(List.of(repositoryId)));
        var target = targets.get(repositoryId).orElseThrow(() -> new ApiFailure(403, "DENIED"));
        var result = queries.discoverWorkflows(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(context.access()), target));
        if (result == null || !target.equals(result.repository().target())
                || result.workflows().size() > limit) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        return new GitHubResources.Workflows(discoveryContext(context, result.link(), result.observedAt()), repository(result.repository()), result.workflows().stream()
                .map(workflow -> new GitHubResources.Workflow(workflow.workflowId(), workflow.name(), workflow.file())).toList());
    }

    @GetMapping("/api/v1/github/repositories/{repositoryId}/workflows/{workflowId}/runs")
    @Operation(summary = "List one bounded page of the workflow's failed runs for self", description = "Read from the GitHub API with the user's token after current access checks. pageToken is opaque.")
    public GitHubResources.Runs runs(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                     @PathVariable String repositoryId, @PathVariable String workflowId,
                                     @RequestParam(defaultValue = "failure") String conclusion,
                                     @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String pageToken) {
        if (!"failure".equals(conclusion)) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        int size = pageSize == null ? DEFAULT_RUN_PAGE_SIZE : pageSize;
        if (size < 1 || size > 100) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        int page = page(pageToken);
        id(workflowId);
        var context = RequestContext.self(jwt, request, limits, clock);
        var targets = resolve(context, ids(List.of(repositoryId)));
        var target = targets.get(repositoryId).orElseThrow(() -> new ApiFailure(403, "DENIED"));
        var result = queries.listFailedRuns(new ListFailedRunsQuery(context.access(), target, workflowId, page, size));
        if (result == null || !target.equals(result.repository().target())) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        var next = result.more() && page < maxRunPages ? token(page + 1) : null;
        return new GitHubResources.Runs(result.runs().stream().map(run -> new GitHubResources.Run(run.runId(), run.attempt(),
                run.conclusion(), run.headBranch(), run.headSha(), run.createdAt(), run.htmlUrl().toString())).toList(), next);
    }

    @GetMapping("/api/v1/github/connection")
    @Operation(summary = "Read minimized self connection metadata", description = "Local metadata is not current GitHub authorization or proof of upstream cleanup.")
    public ResponseEntity<GitHubResources.Connection> connection(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var context = RequestContext.self(jwt, request, limits, clock);
        var result = connections.status(new ConnectionStatusQuery(context.identity()));
        context.requireLive(clock);
        if (result == null) { throw ApiFailure.unavailable(); }
        var response = ResponseEntity.ok();
        result.ifPresent(status -> response.eTag(Long.toString(status.revision())));
        return response.body(new GitHubResources.Connection(result.isPresent() && result.get().activeGenerationId() != null,
                result.map(ConnectionStatus::revision).orElse(null), "NOT_CONFIRMED"));
    }

    @DeleteMapping("/api/v1/github/connection")
    @Operation(summary = "Invalidate the authenticated user's local connection at its expected revision", description = "If-Match contains the quoted revision returned by GET. Cleanup/revocation/purge completion is not claimed.")
    public GitHubResources.Connection disconnect(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                    @RequestHeader("If-Match") String expected) {
        if (!expected.matches("\"[0-9]+\"")) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        long revision = Long.parseLong(expected.substring(1, expected.length() - 1));
        var context = RequestContext.self(jwt, request, limits, clock);
        var status = connections.status(new ConnectionStatusQuery(context.identity())).orElseThrow(() -> new ApiFailure(409, "CONNECTION_CONFLICT"));
        context.requireLive(clock);
        commands.disconnect(new ConnectionCommands.Disconnect(status.connectionId(), revision));
        return new GitHubResources.Connection(false, Math.addExact(revision, 1), "NOT_CONFIRMED");
    }

    private Map<String, Optional<RepositoryTarget>> resolve(RequestContext context, Set<String> ids) {
        context.requireLive(clock);
        var query = new GitHubRepositorySelection.Request(context.access(), ids);
        var start = clock.instant();
        var observed = selections.resolve(query);
        var end = clock.instant();
        context.requireLive(clock);
        if (observed == null || !query.equals(observed.request()) || observed.observedAt() == null
                || observed.observedAt().isBefore(start) || observed.observedAt().isAfter(end)
                || observed.value() == null || !ids.equals(observed.value().keySet())) { throw ApiFailure.unavailable(); }
        for (var entry : observed.value().entrySet()) {
            if (entry.getValue() == null || (entry.getValue().isPresent() && !entry.getKey().equals(entry.getValue().get().repositoryId()))) {
                throw ApiFailure.unavailable();
            }
        }
        return Map.copyOf(observed.value());
    }

    private GitHubResources.AccessVerification access(RequestContext context, Set<String> ids,
                                                       Map<String, Optional<RepositoryTarget>> targets, GitHubViews.Access result) {
        if (result == null || !resolved(targets).equals(result.repositories().keySet())) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        var facts = new ArrayList<GitHubResources.RepositoryFact>();
        for (var id : ids) {
            var target = targets.get(id);
            if (target.isEmpty()) { facts.add(new GitHubResources.RepositoryFact(id, null, null, false, false, false)); continue; }
            var fact = result.repositories().get(target.get());
            if (fact == null || (fact.administrator() && !fact.readable()) || (fact.readable() && (!fact.admitted() || fact.accountId() == null))) {
                throw ApiFailure.unavailable();
            }
            facts.add(new GitHubResources.RepositoryFact(id, fact.readable() ? target.get().installationId() : null,
                    fact.readable() ? fact.accountId() : null, fact.admitted(), fact.readable(), fact.administrator()));
        }
        return new GitHubResources.AccessVerification(context.requestId(), context.access().correlationId(), UUID.fromString(context.access().tenantId()),
                context.actorObjectId(), context.subjectObjectId(), context.access().operation(), result.githubUserId(), result.link().generationId(),
                result.link().revision(), result.observedAt(), context.validUntil(), List.copyOf(facts));
    }
    private void binding(RequestContext context, AccessContext actual, GitHubLink link, java.time.Instant observedAt) {
        context.requireLive(clock);
        if (!context.access().equals(actual) || link == null || link.generationId() == null
                || !context.access().tenantId().equals(link.tenantId()) || !context.access().subjectRef().equals(link.subjectRef())
                || observedAt == null || observedAt.isBefore(context.startedAt()) || observedAt.isAfter(clock.instant())) {
            throw ApiFailure.unavailable();
        }
    }
    private static GitHubResources.DiscoveryContext discoveryContext(RequestContext context, GitHubLink link, java.time.Instant observedAt) {
        return new GitHubResources.DiscoveryContext(context.requestId(), context.access().correlationId(), UUID.fromString(context.access().tenantId()),
                context.actorObjectId(), context.subjectObjectId(), context.access().operation(), link.githubUserId(), link.generationId(), link.revision(),
                observedAt, context.validUntil());
    }
    private Set<String> ids(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > limits.maxBatchSize()) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        values.forEach(this::id);
        var ids = new LinkedHashSet<>(values);
        if (ids.size() != values.size()) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        return Collections.unmodifiableSet(ids);
    }
    private void id(String value) { if (value == null || value.isEmpty() || value.length() > limits.maxContextLength() || !value.matches("[0-9]+")) { throw new ApiFailure(400, "INVALID_REQUEST"); } }
    private int page(String token) {
        if (token == null) { return 1; }
        try {
            var decoded = new String(java.util.Base64.getUrlDecoder().decode(token), java.nio.charset.StandardCharsets.US_ASCII);
            if (!decoded.matches("p[1-9][0-9]{0,3}")) { throw new IllegalArgumentException(); }
            int page = Integer.parseInt(decoded.substring(1));
            if (page > maxRunPages) { throw new IllegalArgumentException(); }
            return page;
        } catch (IllegalArgumentException invalid) { throw new ApiFailure(400, "INVALID_REQUEST"); }
    }
    private static String token(int page) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("p" + page).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    private void page(int limit, String cursor) { if (limit < 1 || limit > limits.maxPageSize() || cursor != null) { throw new ApiFailure(400, "INVALID_REQUEST"); } }
    private static Set<RepositoryTarget> resolved(Map<String, Optional<RepositoryTarget>> selections) {
        var resolved = new HashSet<RepositoryTarget>();
        selections.values().forEach(target -> target.ifPresent(resolved::add));
        return Set.copyOf(resolved);
    }
    private static GitHubResources.Repository repository(GitHubViews.Repository repository) {
        return new GitHubResources.Repository(repository.target().repositoryId(), repository.target().installationId(), repository.fullName(), repository.defaultBranch());
    }
}
