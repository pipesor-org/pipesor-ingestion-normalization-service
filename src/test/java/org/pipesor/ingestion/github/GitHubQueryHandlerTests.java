package org.pipesor.ingestion.github;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.queries.*;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubQueryHandler;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.*;

class GitHubQueryHandlerTests {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RepositoryTarget FIRST = new RepositoryTarget("12042", "451");
    private static final RepositoryTarget SECOND = new RepositoryTarget("33590", "670");
    private static final AccessContext VIEW = context("tenant-a", "entra-alice", "entra-alice", AccessOperation.VIEW, "corr-7");
    private static final AccessContext DISCOVER = context("tenant-a", "entra-alice", "entra-alice", AccessOperation.DISCOVER, "corr-8");
    private static final AccessContext REGISTER = context("tenant-a", "entra-alice", "entra-alice", AccessOperation.REGISTER, "corr-9");

    @Test
    void verifiesExactContextAndPreservesCorrelationWithoutLeakingMetadata() {
        var f = new Fixture();
        var result = f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST, SECOND)));
        assertEquals(VIEW, result.context());
        assertEquals("github-42", result.githubUserId());
        assertEquals(Set.of(FIRST, SECOND), result.repositories().keySet());
        assertTrue(result.repositories().values().stream().allMatch(GitHubViews.RepositoryAccess::readable));
        assertEquals(NOW, result.observedAt());
        assertEquals(2, f.linkReads);
        assertEquals(2, f.admissions.size());
        assertEquals(Set.of("org-one/api", "org-two/worker"), Set.copyOf(f.facts.values().stream().map(RepositoryFacts::fullName).toList()));
        assertFalse(result.toString().contains("org-one/api"));
        assertTrue(f.admissions.stream().allMatch(r -> r.identity().context().equals(VIEW)));
        assertTrue(f.admissions.stream().anyMatch(r -> r.accountId().equals("acct-two") && r.repository().equals(SECOND)));
    }

    @ParameterizedTest
    @EnumSource(AccessOperation.class)
    void returnsSameNonAdministratorFactsForEveryBoundOperationAndPreservesGrantSubject(AccessOperation operation) {
        var f = new Fixture();
        f.facts.put(FIRST, facts(FIRST, true, true, true, true, false));
        var subject = operation.permitsDifferentSubject() ? "entra-recipient" : "entra-alice";
        var ctx = context("tenant-a", "entra-alice", subject, operation, "correlation-" + operation);
        f.link = link(ctx, "github-82", "revision-6");
        var result = f.handler.verifyAccess(new VerifyAccessQuery(ctx, Set.of(FIRST)));
        assertEquals(ctx, result.context());
        assertEquals(new GitHubViews.RepositoryAccess(true, true, false, "acct-one"), result.repositories().get(FIRST));
        assertEquals(subject, f.admissions.getFirst().identity().link().subjectRef());
    }

    @ParameterizedTest
    @EnumSource(value = AccessOperation.class, names = {"VIEW", "OPERATE", "CONFIGURE", "REGISTER", "DISCOVER"})
    void normalOperationRejectsAnotherSubjectBeforeAnyPortCall(AccessOperation operation) {
        assertThrows(IllegalArgumentException.class,
                () -> context("tenant-a", "entra-alice", "entra-other", operation, "corr"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "subject", "actor", "operation", "correlation", "repository", "installation", "identity"})
    void rejectsCrossedProviderRequestBindings(String field) {
        var f = new Fixture();
        f.repositoryEcho = request -> {
            var identity = request.identity();
            var ctx = identity.context();
            var replacement = switch (field) {
                case "tenant" -> context("other-tenant", ctx.actorRef(), ctx.subjectRef(), ctx.operation(), ctx.correlationId());
                case "subject" -> context(ctx.tenantId(), ctx.actorRef(), "entra-other", AccessOperation.GRANT_VIEW, ctx.correlationId());
                case "actor" -> context(ctx.tenantId(), "entra-other", "entra-other", ctx.operation(), ctx.correlationId());
                case "operation" -> context(ctx.tenantId(), ctx.actorRef(), ctx.subjectRef(), AccessOperation.OPERATE, ctx.correlationId());
                case "correlation" -> context(ctx.tenantId(), ctx.actorRef(), ctx.subjectRef(), ctx.operation(), "another-correlation");
                default -> ctx;
            };
            var boundLink = field.equals("identity") ? link(ctx, "another-user", "another-revision") : identity.link();
            var target = switch (field) {
                case "repository" -> new RepositoryTarget("other-repo", FIRST.installationId());
                case "installation" -> new RepositoryTarget(FIRST.repositoryId(), "other-installation");
                default -> request.repository();
            };
            return new ReadOnlyGitHub.RepositoryRequest(new ReadOnlyGitHub.Identity(replacement, boundLink), target);
        };
        failure(UNAVAILABLE, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "subject"})
    void refusesLinkForAnotherTenantOrSubject(String field) {
        var f = new Fixture();
        f.link = new GitHubLink(field.equals("tenant") ? "tenant-b" : VIEW.tenantId(),
                field.equals("subject") ? "entra-other" : VIEW.subjectRef(), "github-42", "revision-1", NOW);
        failure(UNAVAILABLE, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))));
        assertEquals(0, f.providerCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "app", "installation", "sso", "admission"})
    void revokedAccessIsDeniedPerTargetAndSelectionFailsClosed(String revoked) {
        var f = new Fixture();
        f.facts.put(FIRST, facts(FIRST, !revoked.equals("installation"), !revoked.equals("user"),
                !revoked.equals("app"), !revoked.equals("sso"), true));
        f.deniedAdmission = revoked.equals("admission") ? FIRST : null;
        var result = f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST, SECOND)));
        assertFalse(result.repositories().get(FIRST).readable());
        assertFalse(result.repositories().get(FIRST).administrator());
        assertTrue(result.repositories().get(SECOND).readable());
        failure(DENIED, () -> f.handler.selectWorkflow(new SelectWorkflowQuery(REGISTER, FIRST, "82")));
        assertEquals(0, f.workflowReads);
    }

    @Test
    void selectionReturnsProviderMetadataAndAdministrationFactWithoutDecidingRegistration() {
        var f = new Fixture();
        var selected = f.handler.selectWorkflow(new SelectWorkflowQuery(REGISTER, SECOND, "93"));
        assertEquals("org-two/worker", selected.repository().fullName());
        assertNull(selected.repository().defaultBranch());
        assertEquals("Nightly", selected.workflow().name());
        assertEquals(".github/workflows/nightly.yaml", selected.workflow().file());
        assertEquals(REGISTER, selected.access().context());
        assertEquals(new GitHubViews.RepositoryAccess(true, true, true, "acct-two"), selected.access().repositories().get(SECOND));
        f.facts.put(SECOND, facts(SECOND, true, true, true, true, false));
        var nonAdministrator = f.handler.selectWorkflow(new SelectWorkflowQuery(REGISTER, SECOND, "93"));
        assertTrue(nonAdministrator.access().repositories().get(SECOND).readable());
        assertFalse(nonAdministrator.access().repositories().get(SECOND).administrator());
    }

    @Test
    void discoveryFiltersBeforeReturningNamesAndReturnsBothVariedFixturesWhenAdmitted() {
        var f = new Fixture();
        var both = f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
        assertEquals(2, both.repositories().size());
        f.deniedAdmission = SECOND;
        var visible = f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
        assertEquals(List.of(FIRST), visible.repositories().stream().map(GitHubViews.Repository::target).toList());
        assertFalse(visible.toString().contains("org-two"));
        failure(DENIED, () -> f.handler.discoverWorkflows(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(DISCOVER), SECOND)));
        assertEquals(0, f.workflowReads);
    }

    @Test
    void workflowDiscoveryPreservesContextAndIncludesWorkflowsRegardlessOfDefaultBranch() {
        var f = new Fixture();
        var first = f.handler.discoverWorkflows(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(DISCOVER), FIRST));
        var second = f.handler.discoverWorkflows(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(DISCOVER), SECOND));
        assertEquals(DISCOVER, first.context());
        assertEquals("trunk", first.repository().defaultBranch());
        assertNull(second.repository().defaultBranch());
        assertEquals("82", first.workflows().getFirst().workflowId());
        assertEquals("93", second.workflows().getFirst().workflowId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "installation", "workflow"})
    void refusesMetadataForAnotherSelection(String field) {
        var f = new Fixture();
        f.workflowOverride = new WorkflowMetadata(field.equals("repository") ? SECOND :
                field.equals("installation") ? new RepositoryTarget(FIRST.repositoryId(), "other-installation") : FIRST,
                field.equals("workflow") ? "999" : "82", "Other", ".github/workflows/other.yml");
        failure(UNAVAILABLE, () -> f.handler.selectWorkflow(new SelectWorkflowQuery(REGISTER, FIRST, "82")));
        if (!field.equals("workflow")) {
            failure(UNAVAILABLE, () -> f.handler.discoverWorkflows(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(DISCOVER), FIRST)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "changed-user", "same-time-relinked", "updated"})
    void refusesLostOrChangedLinkAtEndOfMultiRead(String change) {
        var f = new Fixture();
        f.finalLink = switch (change) {
            case "missing" -> Optional.empty();
            case "changed-user" -> Optional.of(link(VIEW, "other-user", "revision-2"));
            case "same-time-relinked" -> Optional.of(link(VIEW, f.link.githubUserId(), "revision-2"));
            default -> Optional.of(new GitHubLink(VIEW.tenantId(), VIEW.subjectRef(), "github-42", "revision-1", NOW.plusSeconds(1)));
        };
        failure(CONNECTION_REQUIRED, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))));
    }

    @Test
    void missingLinkBlocksAllProviderCallsAndPreviousSuccessIsNeverCached() {
        var f = new Fixture();
        assertTrue(f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))).repositories().get(FIRST).readable());
        int calls = f.providerCalls;
        f.facts.put(FIRST, facts(FIRST, true, false, true, true, true));
        assertFalse(f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))).repositories().get(FIRST).readable());
        assertTrue(f.providerCalls > calls);
        calls = f.providerCalls;
        f.link = null;
        failure(CONNECTION_REQUIRED, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))));
        assertEquals(calls, f.providerCalls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"link", "currentUser", "admission", "repository", "repositories", "workflow", "workflows"})
    void allPortsRejectStaleAndFutureObservations(String boundary) {
        for (long offset : List.of(-1L, 1L)) {
            var f = new Fixture();
            f.offsetBoundary = boundary;
            f.offset = offset;
            failure(UNAVAILABLE, () -> invokeBoundary(f, boundary));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"link", "currentUser", "admission", "repository", "repositories", "workflow", "workflows"})
    void providerExceptionsAreUnavailableWithoutLeakingSecrets(String boundary) {
        var f = new Fixture();
        String syntheticSecret = "synthetic-provider-secret-743";
        f.failBoundary = boundary;
        f.failure = new IllegalStateException(syntheticSecret, new RuntimeException(syntheticSecret));
        var result = failure(UNAVAILABLE, () -> invokeBoundary(f, boundary));
        var trace = new StringWriter();
        result.printStackTrace(new PrintWriter(trace));
        assertFalse(result.toString().contains(syntheticSecret));
        assertFalse(trace.toString().contains(syntheticSecret));
        assertNull(result.getCause());
        assertEquals(0, result.getSuppressed().length);
    }

    @ParameterizedTest
    @EnumSource(GitHubAccessFailure.Reason.class)
    void safeTypedProviderFailuresSurviveWithoutOriginalException(GitHubAccessFailure.Reason reason) {
        for (var boundary : List.of("currentUser", "repository", "repositories", "workflow", "workflows")) {
            var f = new Fixture();
            f.failBoundary = boundary;
            f.failure = new GitHubAccessFailure(reason);
            var observed = failure(reason, () -> {
                if (boundary.equals("repository")) {
                    f.handler.selectWorkflow(new SelectWorkflowQuery(REGISTER, FIRST, "82"));
                } else { invokeBoundary(f, boundary); }
            });
            assertNotSame(f.failure, observed);
            assertNull(observed.getCause());
        }
    }

    @Test
    void actualUserTokenIdentityMustMatchLinkEvenForEmptyDiscovery() {
        var f = new Fixture();
        f.actualUser = "different-github-user";
        f.facts.clear();
        failure(CONNECTION_REQUIRED, () -> f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER)));
        failure(CONNECTION_REQUIRED, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of())));
        assertTrue(f.admissions.isEmpty());
        f.actualUser = " ";
        failure(UNAVAILABLE, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of())));
    }

    @Test
    void batchPreservesExplicitRepositoryDenialButOutageAbortsWholeResult() {
        var f = new Fixture();
        f.failRepository = SECOND;
        f.failure = new GitHubAccessFailure(DENIED);
        var result = f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST, SECOND)));
        assertTrue(result.repositories().get(FIRST).readable());
        assertFalse(result.repositories().get(SECOND).readable());
        f.failure = new GitHubAccessFailure(UNAVAILABLE);
        failure(UNAVAILABLE, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST, SECOND))));
    }

    @Test
    void admissionRejectsAnotherAccountForTheSameRepositoryAndInstallation() {
        var f = new Fixture();
        f.facts.put(FIRST, new RepositoryFacts(FIRST, "acct-two", true, true, true, true, true,
                "foreign-account/private", "main"));
        var verified = f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST)));
        assertFalse(verified.repositories().get(FIRST).readable());
        var discovered = f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
        assertEquals(List.of(SECOND), discovered.repositories().stream().map(GitHubViews.Repository::target).toList());
        assertFalse(discovered.toString().contains("foreign-account"));
        failure(DENIED, () -> f.handler.selectWorkflow(new SelectWorkflowQuery(REGISTER, FIRST, "82")));
    }

    @Test
    void emptySuccessIsDistinctFromFailureAndEmptyAccessStillChecksProvider() {
        var f = new Fixture();
        f.facts.clear();
        assertTrue(f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER)).repositories().isEmpty());
        assertTrue(f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of())).repositories().isEmpty());
        f.failBoundary = "repositories";
        f.failure = new RuntimeException("timeout or rate limit");
        failure(UNAVAILABLE, () -> f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER)));
        f.failBoundary = "currentUser";
        failure(UNAVAILABLE, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of())));
    }

    @Test
    void admissionFailureAfterOneVisibleCandidateNeverReturnsPartialDiscovery() {
        var f = new Fixture();
        f.failSecondAdmission = true;
        failure(UNAVAILABLE, () -> f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER)));
        assertEquals(2, f.admissions.size());
    }

    @Test
    void malformedOrMismatchedProviderResultsNeverBecomePermission() {
        var f = new Fixture();
        f.facts.put(FIRST, f.facts.get(SECOND));
        failure(UNAVAILABLE, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))));
        f.nullObservation = true;
        failure(UNAVAILABLE, () -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST))));
    }

    @Test
    void queryInputsAreValidatedAndCollectionsAreImmutable() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryTarget(" ", "install"));
        assertThrows(IllegalArgumentException.class, () -> context("tenant", "actor", "actor", AccessOperation.VIEW, ""));
        assertThrows(IllegalArgumentException.class, () -> new DiscoverRepositoriesQuery(VIEW));
        assertThrows(IllegalArgumentException.class, () -> new SelectWorkflowQuery(VIEW, FIRST, "82"));
        var f = new Fixture();
        var result = f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST)));
        assertThrows(UnsupportedOperationException.class, () -> result.repositories().clear());
    }

    private static void invokeBoundary(Fixture f, String boundary) {
        switch (boundary) {
            case "repositories" -> f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
            case "workflow" -> f.handler.selectWorkflow(new SelectWorkflowQuery(REGISTER, FIRST, "82"));
            case "workflows" -> f.handler.discoverWorkflows(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(DISCOVER), FIRST));
            default -> f.handler.verifyAccess(new VerifyAccessQuery(VIEW, Set.of(FIRST)));
        }
    }

    private static GitHubAccessFailure failure(GitHubAccessFailure.Reason reason, Runnable action) {
        var failure = assertThrows(GitHubAccessFailure.class, action::run);
        assertEquals(reason, failure.reason());
        return failure;
    }

    private static AccessContext context(String tenant, String actor, String subject, AccessOperation operation, String correlation) {
        return new AccessContext(tenant, actor, subject, operation, correlation);
    }

    private static GitHubLink link(AccessContext context, String user, String revision) {
        return new GitHubLink(context.tenantId(), context.subjectRef(), user, revision, NOW.minusSeconds(86400));
    }

    private static RepositoryFacts facts(RepositoryTarget target, boolean active, boolean user, boolean app, boolean sso, boolean admin) {
        boolean first = target.equals(FIRST);
        return new RepositoryFacts(target, first ? "acct-one" : "acct-two", active, user, app, sso, admin,
                first ? "org-one/api" : "org-two/worker", first ? "trunk" : null);
    }

    private static final class Fixture implements ReadOnlyGitHub {
        GitHubLink link = GitHubQueryHandlerTests.link(VIEW, "github-42", "revision-1");
        Optional<GitHubLink> finalLink;
        final Map<RepositoryTarget, RepositoryFacts> facts = new HashMap<>();
        final List<InstallationAdmission.Request> admissions = new ArrayList<>();
        RepositoryTarget deniedAdmission;
        RepositoryTarget failRepository;
        String actualUser;
        Function<RepositoryRequest, RepositoryRequest> repositoryEcho = Function.identity();
        WorkflowMetadata workflowOverride;
        String offsetBoundary;
        long offset;
        String failBoundary;
        RuntimeException failure;
        boolean failSecondAdmission;
        boolean nullObservation;
        int linkReads;
        int providerCalls;
        int workflowReads;
        final GitHubQueryHandler handler = new GitHubQueryHandler(this::link, this::admission, this, CLOCK);

        Fixture() {
            facts.put(FIRST, facts(FIRST, true, true, true, true, true));
            facts.put(SECOND, facts(SECOND, true, true, true, true, true));
        }

        Observation<AccessContext, Optional<GitHubLink>> link(AccessContext request) {
            linkReads++;
            var current = finalLink != null && linkReads > 1 ? finalLink : Optional.ofNullable(link);
            return observed("link", request, current);
        }

        Observation<InstallationAdmission.Request, Boolean> admission(InstallationAdmission.Request request) {
            admissions.add(request);
            if (failSecondAdmission && admissions.size() == 2) { throw new RuntimeException("provider failure"); }
            boolean knownAccountInstallation = request.repository().equals(FIRST) && request.accountId().equals("acct-one")
                    || request.repository().equals(SECOND) && request.accountId().equals("acct-two");
            return observed("admission", request, request.identity().context().tenantId().equals("tenant-a")
                    && knownAccountInstallation && !request.repository().equals(deniedAdmission));
        }

        @Override
        public Observation<RepositoryRequest, RepositoryFacts> repository(RepositoryRequest request) {
            providerCalls++;
            if (request.repository().equals(failRepository)) { throw failure; }
            return observed("repository", repositoryEcho.apply(request), facts.get(request.repository()));
        }

        @Override
        public Observation<Identity, String> currentUser(Identity request) {
            providerCalls++;
            return observed("currentUser", request, actualUser == null ? request.link().githubUserId() : actualUser);
        }

        @Override
        public Observation<Identity, List<RepositoryFacts>> repositories(Identity request) {
            providerCalls++;
            return observed("repositories", request, List.copyOf(facts.values()));
        }

        @Override
        public Observation<RepositoryRequest, List<WorkflowMetadata>> workflows(RepositoryRequest request) {
            workflowReads++;
            return observed("workflows", request, List.of(metadata(request.repository())));
        }

        @Override
        public Observation<WorkflowRequest, WorkflowMetadata> workflow(WorkflowRequest request) {
            workflowReads++;
            return observed("workflow", request, metadata(request.repository().repository()));
        }

        WorkflowMetadata metadata(RepositoryTarget target) {
            if (workflowOverride != null) { return workflowOverride; }
            return new WorkflowMetadata(target, target.equals(FIRST) ? "82" : "93", target.equals(FIRST) ? "Build" : "Nightly",
                    target.equals(FIRST) ? ".github/workflows/build.yml" : ".github/workflows/nightly.yaml");
        }

        <Q, T> Observation<Q, T> observed(String boundary, Q request, T value) {
            if (boundary.equals(failBoundary)) { throw failure; }
            if (nullObservation) { return null; }
            return new Observation<>(request, value, NOW.plusSeconds(boundary.equals(offsetBoundary) ? offset : 0));
        }
    }
}
