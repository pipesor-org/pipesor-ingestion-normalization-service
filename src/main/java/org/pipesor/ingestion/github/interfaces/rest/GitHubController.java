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
import org.pipesor.ingestion.github.interfaces.rest.transform.DiscoveryPageTokens;
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
    private static final int DEFAULT_COLLABORATOR_PAGE_SIZE = 30;
    private static final String LIMIT = "Legacy form: complete list of at most limit items (1-100); excludes pageSize, pageToken and q";
    private static final String PAGE_SIZE = "Paged form (required): items per page (1-100)";
    private static final String PAGE_TOKEN = "Paged form: opaque nextPageToken of the previous page, valid only for the same route, repository and q";
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
    @Operation(summary = "Discover the authenticated user's admitted and readable repositories",
            description = "Reconciles repository admission first (ADR-024). Exactly one form: paged (pageSize, optional pageToken and q) "
                    + "returns one page ordered by fullName case-insensitively, then numeric repositoryId, plus nextPageToken; "
                    + "legacy (limit only) returns the complete list, or 503 VERIFICATION_UNAVAILABLE when it exceeds limit. "
                    + "Both forms, neither form or cursor are 400 INVALID_REQUEST; a malformed pageToken, or one issued for another "
                    + "route, repository or q, is 400 INVALID_PAGE_TOKEN.")
    @ApiResponse(responseCode = "200", description = "Discovery context, repositories and nextPageToken",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GitHubResources.Repositories.class)))
    public GitHubResources.Repositories repositories(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                       @Parameter(description = LIMIT, schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100")) @RequestParam(required = false) Integer limit,
                                                       @Parameter(description = PAGE_SIZE, schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100")) @RequestParam(required = false) Integer pageSize,
                                                       @Parameter(description = PAGE_TOKEN, schema = @Schema(maxLength = DiscoveryPageTokens.MAX_LENGTH)) @RequestParam(required = false) String pageToken,
                                                       @Parameter(description = "Case-insensitive partial match on fullName; 2-100 characters after trimming",
                                                               schema = @Schema(minLength = 2, maxLength = 100)) @RequestParam(required = false) String q) {
        var form = form(request, limit, pageSize, pageToken, q);
        var context = RequestContext.self(jwt, request, limits, clock);
        var discovery = new DiscoverRepositoriesQuery(context.access());
        if (form.legacy()) {
            var result = queries.discoverRepositories(discovery);
            if (result == null || result.repositories().size() > form.size()) { throw ApiFailure.unavailable(); }
            binding(context, result.context(), result.link(), result.observedAt());
            return new GitHubResources.Repositories(discoveryContext(context, result.link(), result.observedAt()),
                    result.repositories().stream().map(GitHubController::repository).toList(), null);
        }
        var tokens = DiscoveryPageTokens.Binding.repositories(context.access().subjectRef(), form.filter());
        var page = new DiscoveryPage(form.size(), form.filter(), DiscoveryPageTokens.decode(pageToken, tokens));
        var result = queries.discoverRepositoryPage(new DiscoverRepositoryPageQuery(discovery, page));
        if (result == null || result.repositories().size() > form.size()) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        return new GitHubResources.Repositories(discoveryContext(context, result.link(), result.observedAt()),
                result.repositories().stream().map(GitHubController::repository).toList(), DiscoveryPageTokens.encode(result.next(), tokens));
    }

    @GetMapping("/api/v1/github/installations")
    @Operation(summary = "List the user's pipesor installations and repository admission (ADR-024)",
            description = "Read with the user's token. Repositories the user administers in a pipesor installation are admitted; "
                    + "admissions of repositories an installation no longer lists are revoked. Browser-supplied installation data is never used.")
    public GitHubResources.Installations installations(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var context = RequestContext.self(jwt, request, limits, clock);
        var result = queries.listInstallations(new DiscoverRepositoriesQuery(context.access()));
        if (result == null) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        return new GitHubResources.Installations(discoveryContext(context, result.link(), result.observedAt()),
                result.installations().stream().map(installation -> new GitHubResources.Installation(installation.installationId(),
                        new GitHubResources.Account(installation.accountId(), installation.accountLogin()), !installation.active(),
                        installation.repositories().stream().map(repository -> new GitHubResources.RepositoryAdmission(
                                repository.repositoryId(), repository.fullName(), repository.admitted(), repository.userIsAdmin())).toList()))
                        .toList());
    }

    @GetMapping("/api/v1/github/repositories/{repositoryId}/workflows")
    @Operation(summary = "Discover current workflows of one readable repository for self",
            description = "Exactly one form: paged (pageSize, optional pageToken and q) returns one page ordered by name "
                    + "case-insensitively, then numeric workflowId, plus nextPageToken; legacy (limit only) returns the complete list, "
                    + "or 503 VERIFICATION_UNAVAILABLE when it exceeds limit. Both forms, neither form or cursor are 400 INVALID_REQUEST; "
                    + "a malformed pageToken, or one issued for another route, repository or q, is 400 INVALID_PAGE_TOKEN.")
    @ApiResponse(responseCode = "200", description = "Discovery context, repository, workflows and nextPageToken",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GitHubResources.Workflows.class)))
    public GitHubResources.Workflows workflows(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                 @PathVariable String repositoryId,
                                                 @Parameter(description = LIMIT, schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100")) @RequestParam(required = false) Integer limit,
                                                 @Parameter(description = PAGE_SIZE, schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100")) @RequestParam(required = false) Integer pageSize,
                                                 @Parameter(description = PAGE_TOKEN, schema = @Schema(maxLength = DiscoveryPageTokens.MAX_LENGTH)) @RequestParam(required = false) String pageToken,
                                                 @Parameter(description = "Case-insensitive partial match on the workflow name or file; 2-100 characters after trimming",
                                                         schema = @Schema(minLength = 2, maxLength = 100)) @RequestParam(required = false) String q) {
        var form = form(request, limit, pageSize, pageToken, q);
        var context = RequestContext.self(jwt, request, limits, clock);
        var ids = ids(List.of(repositoryId));
        var tokens = DiscoveryPageTokens.Binding.workflows(repositoryId, context.access().subjectRef(), form.filter());
        var after = form.legacy() ? null : DiscoveryPageTokens.decode(pageToken, tokens);
        var targets = resolve(context, ids);
        var target = targets.get(repositoryId).orElseThrow(() -> new ApiFailure(403, "DENIED"));
        var discovery = new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(context.access()), target);
        if (form.legacy()) {
            var result = queries.discoverWorkflows(discovery);
            if (result == null || !target.equals(result.repository().target())
                    || result.workflows().size() > form.size()) { throw ApiFailure.unavailable(); }
            binding(context, result.context(), result.link(), result.observedAt());
            return new GitHubResources.Workflows(discoveryContext(context, result.link(), result.observedAt()), repository(result.repository()),
                    workflows(result.workflows()), null);
        }
        var result = queries.discoverWorkflowPage(new DiscoverWorkflowPageQuery(discovery, new DiscoveryPage(form.size(), form.filter(), after)));
        if (result == null || !target.equals(result.repository().target())
                || result.workflows().size() > form.size()) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        return new GitHubResources.Workflows(discoveryContext(context, result.link(), result.observedAt()), repository(result.repository()),
                workflows(result.workflows()), DiscoveryPageTokens.encode(result.next(), tokens));
    }

    @GetMapping("/api/v1/github/repositories/{repositoryId}/workflows/{workflowId}/runs")
    @Operation(summary = "List one bounded page of the workflow's failed or successful runs for self", description = "Read from the GitHub API with the user's token after current access checks. conclusion is failure (default, runs to analyse) or success (runs to record as verification). pageToken is opaque.")
    public GitHubResources.Runs runs(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                     @PathVariable String repositoryId, @PathVariable String workflowId,
                                     @RequestParam(defaultValue = "failure") String conclusion,
                                     @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String pageToken) {
        if (!ListRunsQuery.CONCLUSIONS.contains(conclusion)) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        int size = pageSize == null ? DEFAULT_RUN_PAGE_SIZE : pageSize;
        if (size < 1 || size > 100) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        int page = page(pageToken);
        id(workflowId);
        var context = RequestContext.self(jwt, request, limits, clock);
        var targets = resolve(context, ids(List.of(repositoryId)));
        var target = targets.get(repositoryId).orElseThrow(() -> new ApiFailure(403, "DENIED"));
        var result = queries.listRuns(new ListRunsQuery(context.access(), target, workflowId, conclusion, page, size));
        if (result == null || !target.equals(result.repository().target())) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        var next = result.more() && page < maxRunPages ? token(page + 1) : null;
        return new GitHubResources.Runs(result.runs().stream().map(run -> new GitHubResources.Run(run.runId(), run.attempt(),
                run.conclusion(), run.headBranch(), run.headSha(), run.createdAt(), run.runStartedAt(), run.htmlUrl().toString())).toList(), next);
    }

    @GetMapping("/api/v1/github/repositories/{repositoryId}/collaborators")
    @Operation(summary = "List the GitHub collaborators of a repository the caller currently administers",
            description = "Requires an active GitHub link (else 409 CONNECTION_REQUIRED), the repository admitted in a pipesor "
                    + "installation and readable, and the caller's current GitHub admin permission on it, read with the caller's "
                    + "own token (else 403 DENIED; GitHub's collaborators are not read then). Returns one GitHub page "
                    + "(affiliation=all) in GitHub's order without bots or organizations, so a page may hold fewer than pageSize "
                    + "items: only nextPageToken (null on the last page) signals more. pipesorConnection discloses to the "
                    + "repository administrator whether each collaborator currently holds an active Pipesor GitHub link, because "
                    + "grants can only target linked users (ADR-028). Repeated parameters, a non-numeric repositoryId or pageSize "
                    + "outside 1-100 are 400 INVALID_REQUEST; a malformed pageToken, or one issued for another repository, subject "
                    + "or pageSize, is 400 INVALID_PAGE_TOKEN.")
    @ApiResponse(responseCode = "200", description = "Repository, one page of collaborators and nextPageToken",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GitHubResources.Collaborators.class)))
    public GitHubResources.Collaborators collaborators(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
                                                        @PathVariable String repositoryId,
                                                        @Parameter(description = "Collaborators requested from GitHub per page (1-100)",
                                                                schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100",
                                                                        defaultValue = "" + DEFAULT_COLLABORATOR_PAGE_SIZE)) @RequestParam(required = false) Integer pageSize,
                                                        @Parameter(description = "Opaque nextPageToken of the previous page, valid only for the same repository, subject and pageSize",
                                                                schema = @Schema(maxLength = DiscoveryPageTokens.MAX_LENGTH)) @RequestParam(required = false) String pageToken) {
        int size = collaboratorPageSize(request, pageSize);
        var ids = ids(List.of(repositoryId));
        var context = RequestContext.self(jwt, request, limits, clock);
        var tokens = DiscoveryPageTokens.Binding.collaborators(repositoryId, context.access().subjectRef(), size);
        int page = DiscoveryPageTokens.decodePage(pageToken, tokens, ReadOnlyGitHub.CollaboratorsRequest.MAX_PAGE);
        var target = resolve(context, ids).get(repositoryId).orElseThrow(() -> new ApiFailure(403, "DENIED"));
        var result = queries.listCollaborators(new ListCollaboratorsQuery(context.access(), target, page, size));
        if (result == null || result.repository() == null || !target.equals(result.repository().target())
                || result.collaborators().size() > size) { throw ApiFailure.unavailable(); }
        binding(context, result.context(), result.link(), result.observedAt());
        var tenant = context.access().tenantId() + ":";
        var items = new ArrayList<GitHubResources.Collaborator>();
        for (var collaborator : result.collaborators()) {
            if (collaborator.principalRef() == null || !collaborator.principalRef().startsWith(tenant)) { throw ApiFailure.unavailable(); }
            items.add(new GitHubResources.Collaborator(collaborator.githubUserId(), collaborator.login(),
                    collaborator.avatarUrl() == null ? null : collaborator.avatarUrl().toString(), collaborator.role(), collaborator.principalRef(),
                    collaborator.linked() ? GitHubResources.PipesorConnection.ACTIVE : GitHubResources.PipesorConnection.NONE));
        }
        var next = result.more() && page < ReadOnlyGitHub.CollaboratorsRequest.MAX_PAGE ? DiscoveryPageTokens.encodePage(page + 1, tokens) : null;
        return new GitHubResources.Collaborators(new GitHubResources.RepositoryReference(target.repositoryId(), result.repository().fullName()),
                List.copyOf(items), next);
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
    /** The collaborator page size: absent means the default; repeated, empty or out-of-range values are invalid. */
    private static int collaboratorPageSize(HttpServletRequest request, Integer pageSize) {
        for (var name : List.of("pageSize", "pageToken")) {
            var values = request.getParameterValues(name);
            if (values != null && values.length > 1) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        }
        if (pageSize == null && request.getParameter("pageSize") != null) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        int size = pageSize == null ? DEFAULT_COLLABORATOR_PAGE_SIZE : pageSize;
        if (size < 1 || size > ReadOnlyGitHub.CollaboratorsRequest.MAX_PAGE_SIZE) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        return size;
    }
    /** The discovery form: legacy {@code limit} alone, or paged {@code pageSize} with optional {@code pageToken} and {@code q}. */
    private record DiscoveryForm(boolean legacy, int size, String filter) {}
    private DiscoveryForm form(HttpServletRequest request, Integer limit, Integer pageSize, String pageToken, String q) {
        for (var name : List.of("pageSize", "pageToken", "q")) {
            var values = request.getParameterValues(name);
            if (values != null && values.length > 1) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        }
        boolean paged = pageSize != null || pageToken != null || q != null;
        if (request.getParameterMap().containsKey("cursor") || paged == (limit != null)) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        int size = paged ? Objects.requireNonNullElse(pageSize, 0) : limit;
        int max = paged ? Math.min(limits.maxPageSize(), DiscoveryPage.MAX_SIZE) : limits.maxPageSize();
        if (size < 1 || size > max) { throw new ApiFailure(400, "INVALID_REQUEST"); }
        try { return new DiscoveryForm(!paged, size, DiscoveryPage.filter(q)); }
        catch (IllegalArgumentException invalid) { throw new ApiFailure(400, "INVALID_REQUEST"); }
    }
    private static List<GitHubResources.Workflow> workflows(List<WorkflowMetadata> workflows) {
        return workflows.stream().map(workflow -> new GitHubResources.Workflow(workflow.workflowId(), workflow.name(), workflow.file())).toList();
    }
    private static Set<RepositoryTarget> resolved(Map<String, Optional<RepositoryTarget>> selections) {
        var resolved = new HashSet<RepositoryTarget>();
        selections.values().forEach(target -> target.ifPresent(resolved::add));
        return Set.copyOf(resolved);
    }
    private static GitHubResources.Repository repository(GitHubViews.Repository repository) {
        return new GitHubResources.Repository(repository.target().repositoryId(), repository.target().installationId(), repository.fullName(), repository.defaultBranch());
    }
}
