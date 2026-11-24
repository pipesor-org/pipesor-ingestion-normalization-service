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
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub.Collaborator;
import org.pipesor.ingestion.github.application.queries.*;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubQueryHandler;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.shared.security.PlatformIds;

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
        // Only a repository the user administers can be admitted on discovery (ADR-024); a reader stays filtered.
        f.facts.put(SECOND, facts(SECOND, true, true, true, true, false));
        var visible = f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
        assertEquals(List.of(FIRST), visible.repositories().stream().map(GitHubViews.Repository::target).toList());
        assertFalse(visible.toString().contains("org-two"));
        failure(DENIED, () -> f.handler.discoverWorkflows(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(DISCOVER), SECOND)));
        assertEquals(0, f.workflowReads);
    }

    @Test
    void failedRunsAreListedOnlyForAnAdmittedReadableRepositoryAndRejectDuplicates() {
        var f = new Fixture();
        var page = f.handler.listRuns(new ListRunsQuery(DISCOVER, FIRST, "82", "failure", 1, 24));
        assertEquals(List.of("7001"), page.runs().stream().map(ReadOnlyGitHub.WorkflowRun::runId).toList());
        assertTrue(page.more());
        assertEquals(DISCOVER, page.context());
        f.deniedAdmission = SECOND;
        failure(DENIED, () -> f.handler.listRuns(new ListRunsQuery(DISCOVER, SECOND, "93", "failure", 1, 24)));
        f.deniedAdmission = null;
        f.duplicateRuns = true;
        failure(UNAVAILABLE, () -> f.handler.listRuns(new ListRunsQuery(DISCOVER, FIRST, "82", "failure", 1, 24)));
        assertThrows(IllegalArgumentException.class, () -> new ListRunsQuery(DISCOVER, FIRST, "82", "failure", 1, 101));
        // Only failed runs (to analyse) and successful runs (to verify) can be listed.
        assertThrows(IllegalArgumentException.class, () -> new ListRunsQuery(DISCOVER, FIRST, "82", "cancelled", 1, 24));
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

    @Test
    void pagedWorkflowDiscoveryResumesStrictlyAfterThePositionEvenWhenItemsVanishOrKeysAreBounded() {
        var f = new Fixture();
        var longName = "X".repeat(400);
        f.workflowList = List.of(workflow("4", "y"), workflow("3", longName + "c"), workflow("12", longName + "B"),
                workflow("2", longName + "b"), workflow("1", longName + "a"));
        var first = workflowPage(f, 2, null, null);
        assertEquals(List.of("1", "2"), workflowIds(first));
        assertEquals(new DiscoveryPage.Position(DiscoveryPage.key(longName + "b"), "2", false), first.next());
        assertEquals(DISCOVER, first.context());
        assertEquals(FIRST, first.repository().target());
        assertEquals(List.of("12", "3"), workflowIds(workflowPage(f, 2, null, first.next())));
        // A bounded (prefix) position resumes after its exact item while that item still exists ...
        var bounded = new DiscoveryPage.Position("x".repeat(300), "2", true);
        assertEquals(List.of("12", "3", "4"), workflowIds(workflowPage(f, 10, null, bounded)));
        // ... and otherwise repeats the items sharing the prefix rather than skipping any.
        f.workflowList = f.workflowList.stream().filter(workflow -> !workflow.workflowId().equals("2")).toList();
        assertEquals(List.of("1", "12", "3", "4"), workflowIds(workflowPage(f, 10, null, bounded)));
        assertEquals(List.of("12", "3"), workflowIds(workflowPage(f, 2, null, first.next())));
        assertNull(workflowPage(f, 2, null, new DiscoveryPage.Position(DiscoveryPage.key(longName + "c"), "3", false)).next());
        var filtered = workflowPage(f, 10, DiscoveryPage.filter(" XXB "), null);
        assertEquals(List.of("12"), workflowIds(filtered));
        f.workflowList = List.of(workflow("1", "build"), new WorkflowMetadata(FIRST, "2", "deploy", ".github/workflows/Build-Release.yml"));
        assertEquals(List.of("1", "2"), workflowIds(workflowPage(f, 10, DiscoveryPage.filter("BUILD"), null)));
        f.workflowList = List.of(workflow("1", "build"), workflow("not-numeric", "test"));
        failure(UNAVAILABLE, () -> workflowPage(f, 10, null, null));
    }

    @Test
    void pagedRepositoryDiscoveryReconcilesAndRefusesAnAmbiguousOrder() {
        var f = new Fixture();
        var page = f.handler.discoverRepositoryPage(new DiscoverRepositoryPageQuery(new DiscoverRepositoriesQuery(DISCOVER),
                new DiscoveryPage(1, null, null)));
        assertEquals(List.of(FIRST), page.repositories().stream().map(GitHubViews.Repository::target).toList());
        assertEquals(new DiscoveryPage.Position("org-one/api", FIRST.repositoryId(), false), page.next());
        assertEquals(2, f.revocations.size(), "admissions are reconciled for the page");
        var rest = f.handler.discoverRepositoryPage(new DiscoverRepositoryPageQuery(new DiscoverRepositoriesQuery(DISCOVER),
                new DiscoveryPage(1, null, page.next())));
        assertEquals(List.of(SECOND), rest.repositories().stream().map(GitHubViews.Repository::target).toList());
        assertNull(rest.next());
        var twin = new RepositoryTarget(FIRST.repositoryId(), "999");
        f.facts.put(twin, new RepositoryFacts(twin, "acct-three", true, true, true, true, true, "ORG-ONE/API", null));
        assertEquals(3, f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER)).repositories().size());
        failure(UNAVAILABLE, () -> f.handler.discoverRepositoryPage(new DiscoverRepositoryPageQuery(
                new DiscoverRepositoriesQuery(DISCOVER), new DiscoveryPage(10, null, null))));
    }

    @Test
    void discoveryPageBoundsSizeFilterAndPosition() {
        assertEquals("ab", DiscoveryPage.filter("  Ab \t"));
        assertNull(DiscoveryPage.filter(null));
        assertEquals("\uD83D\uDE00".repeat(100), DiscoveryPage.filter("\uD83D\uDE00".repeat(100)));
        for (var invalid : List.of("", " a ", "a".repeat(101), "\uD83D\uDE00".repeat(101))) {
            assertThrows(IllegalArgumentException.class, () -> DiscoveryPage.filter(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryPage(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryPage(101, null, null));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryPage(5, " ", null));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryPage.Position("", "1", false));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryPage.Position("a", "1a", false));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryPage.Position("a", "", false));
    }

    private static GitHubViews.WorkflowPage workflowPage(Fixture f, int size, String filter, DiscoveryPage.Position after) {
        return f.handler.discoverWorkflowPage(new DiscoverWorkflowPageQuery(new DiscoverWorkflowsQuery(new DiscoverRepositoriesQuery(DISCOVER), FIRST),
                new DiscoveryPage(size, filter, after)));
    }

    private static List<String> workflowIds(GitHubViews.WorkflowPage page) {
        return page.workflows().stream().map(WorkflowMetadata::workflowId).toList();
    }

    private static WorkflowMetadata workflow(String id, String name) {
        return new WorkflowMetadata(FIRST, id, name, ".github/workflows/" + id + ".yml");
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

    // ADR-024: repository administrator self-service admission.
    private static final RepositoryTarget THIRD = new RepositoryTarget("77001", "670");

    @Test
    void anAdministratorsRepositoryIsAdmittedOnDiscoveryAndListedAsAdmitted() {
        var f = new Fixture();
        f.facts.put(THIRD, facts(THIRD, true, true, true, true, true));
        var discovered = f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
        assertTrue(discovered.repositories().stream().anyMatch(repository -> repository.target().equals(THIRD)));
        assertEquals(java.util.Set.of(THIRD), f.selfAdmitted);
        var installation = f.handler.listInstallations(new DiscoverRepositoriesQuery(DISCOVER)).installations().stream()
                .filter(candidate -> candidate.installationId().equals("670")).findFirst().orElseThrow();
        var third = installation.repositories().stream().filter(repository -> repository.repositoryId().equals("77001")).findFirst().orElseThrow();
        assertTrue(third.admitted() && third.userIsAdmin());
        assertEquals("acct-two", installation.accountId());
    }

    @Test
    void aReaderWhoIsNotAdministratorAdmitsNothing() {
        var f = new Fixture();
        f.facts.put(THIRD, facts(THIRD, true, true, true, true, false));
        var discovered = f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
        assertFalse(discovered.repositories().stream().anyMatch(repository -> repository.target().equals(THIRD)));
        assertTrue(f.selfAdmitted.isEmpty());
        var third = f.handler.listInstallations(new DiscoverRepositoriesQuery(DISCOVER)).installations().stream()
                .flatMap(installation -> installation.repositories().stream())
                .filter(repository -> repository.repositoryId().equals("77001")).findFirst().orElseThrow();
        assertFalse(third.admitted() || third.userIsAdmin());
    }

    @Test
    void onlyRepositoriesListedByTheInstallationStayAdmitted() {
        var f = new Fixture();
        f.revokedOnNextCall = java.util.Set.of("99999");
        f.handler.discoverRepositories(new DiscoverRepositoriesQuery(DISCOVER));
        // One revocation pass per installation, carrying exactly what GitHub lists there; nothing else is admitted.
        assertEquals(2, f.revocations.size());
        var forSecond = f.revocations.stream().filter(revocation -> revocation.installationId().equals("670")).findFirst().orElseThrow();
        assertEquals(java.util.Set.of("33590"), forSecond.listedRepositoryIds());
        assertEquals(DISCOVER.subjectRef(), forSecond.subjectRef());
        assertTrue(f.selfAdmitted.isEmpty(), "a repository GitHub does not list in the installation is never admitted");
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

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String CALLER = TENANT + ":" + PlatformIds.human(42);
    private static final AccessContext ADMINISTER = context(TENANT, CALLER, CALLER, AccessOperation.DISCOVER, "corr-10");

    private static Fixture administrator() {
        var f = new Fixture();
        f.admittedTenant = TENANT;
        f.link = link(ADMINISTER, "42", "revision-1");
        return f;
    }

    private static Collaborator collaborator(String id, String login, CollaboratorRole role) {
        return new Collaborator(id, login, java.net.URI.create("https://avatars.githubusercontent.com/u/" + id + "?v=4"), role);
    }

    @Test
    void administratorSeesOneGitHubPageJoinedWithPipesorLinksInOneBatch() {
        var f = administrator();
        f.collaboratorList = List.of(collaborator("583231", "octocat", CollaboratorRole.ADMIN), new Collaborator("7", "reader", null, CollaboratorRole.READ));
        f.linkedUsers = Set.of("583231");
        f.moreCollaborators = true;
        var result = f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 3, 2));
        assertEquals(ADMINISTER, result.context());
        assertEquals(f.link, result.link());
        assertEquals(NOW, result.observedAt());
        assertEquals(new GitHubViews.Repository(FIRST, "org-one/api", "trunk"), result.repository());
        assertEquals(List.of("583231", "7"), result.collaborators().stream().map(GitHubViews.Collaborator::githubUserId).toList());
        var first = result.collaborators().get(0);
        assertEquals(TENANT + ":" + PlatformIds.human(583231), first.principalRef());
        assertEquals(first.principalRef(), first.principalRef().toLowerCase(java.util.Locale.ROOT));
        assertTrue(first.linked());
        assertEquals(CollaboratorRole.ADMIN, first.role());
        assertFalse(result.collaborators().get(1).linked());
        assertNull(result.collaborators().get(1).avatarUrl());
        assertTrue(result.more());
        assertEquals(3, f.lastCollaborators.page());
        assertEquals(2, f.lastCollaborators.pageSize());
        assertEquals(1, f.connectionReads.size(), "one batched link read per page");
        assertEquals(java.util.UUID.fromString(TENANT), f.connectionReads.getFirst().tenantId());
        assertEquals(Set.of(new PipesorConnections.Principal(PlatformIds.human(583231), "583231"), new PipesorConnections.Principal(PlatformIds.human(7), "7")),
                f.connectionReads.getFirst().principals());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader", "unreadable", "unadmitted", "suspended"})
    void onlyACurrentAdministratorOfAnAdmittedReadableRepositoryReachesCollaborators(String scenario) {
        var f = administrator();
        f.collaboratorList = List.of(collaborator("583231", "octocat", CollaboratorRole.ADMIN));
        switch (scenario) {
            case "reader" -> f.facts.put(FIRST, facts(FIRST, true, true, true, true, false));
            case "unreadable" -> f.facts.put(FIRST, facts(FIRST, true, false, true, true, true));
            case "suspended" -> f.facts.put(FIRST, facts(FIRST, false, true, true, true, true));
            default -> f.admittedTenant = "another-tenant";
        }
        failure(DENIED, () -> f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 1, 30)));
        assertEquals(0, f.collaboratorReads);
        assertTrue(f.connectionReads.isEmpty());
    }

    @Test
    void emptyPageSkipsTheLinkReadAndInconsistentPagesAreUnavailable() {
        var f = administrator();
        var empty = f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 1000, 100));
        assertTrue(empty.collaborators().isEmpty());
        assertFalse(empty.more());
        assertTrue(f.connectionReads.isEmpty());
        f.collaboratorList = List.of(collaborator("583231", "octocat", CollaboratorRole.ADMIN), collaborator("583231", "copy", CollaboratorRole.READ));
        failure(UNAVAILABLE, () -> f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 1, 30)));
        for (var id : List.of("007", "0", "-1", "92233720368547758070", "x")) {
            f.collaboratorList = List.of(collaborator(id, "octocat", CollaboratorRole.ADMIN));
            failure(UNAVAILABLE, () -> f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 1, 30)));
        }
        f.collaboratorList = List.of(collaborator("1", "a", CollaboratorRole.READ), collaborator("2", "b", CollaboratorRole.READ));
        failure(UNAVAILABLE, () -> f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 1, 1)));
        f.extraLinked = Set.of(new PipesorConnections.Principal(PlatformIds.human(3), "3"));
        failure(UNAVAILABLE, () -> f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 1, 30)));
        f.extraLinked = Set.of();
        f.failBoundary = "connections";
        f.failure = new RuntimeException("database outage");
        failure(UNAVAILABLE, () -> f.handler.listCollaborators(new ListCollaboratorsQuery(ADMINISTER, FIRST, 1, 30)));
    }

    @Test
    void principalReferencesNeedTheCanonicalTenantBeforeAnyRead() {
        var f = administrator();
        for (var tenant : List.of("ABCDEF01-1111-4111-8111-111111111111", "1-1-1-1-1", "tenant-a")) {
            var context = context(tenant, "actor", "actor", AccessOperation.DISCOVER, "corr");
            f.link = link(context, "42", "revision-1");
            f.admittedTenant = tenant;
            failure(UNAVAILABLE, () -> f.handler.listCollaborators(new ListCollaboratorsQuery(context, FIRST, 1, 30)));
        }
        assertEquals(0, f.providerCalls);
        assertEquals(0, f.linkReads);
        for (int[] bounds : new int[][]{{0, 30}, {1001, 30}, {1, 0}, {1, 101}}) {
            assertThrows(IllegalArgumentException.class, () -> new ListCollaboratorsQuery(ADMINISTER, FIRST, bounds[0], bounds[1]));
        }
        assertThrows(IllegalArgumentException.class, () -> new ListCollaboratorsQuery(VIEW, FIRST, 1, 30));
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
        List<WorkflowMetadata> workflowList;
        String offsetBoundary;
        long offset;
        String failBoundary;
        RuntimeException failure;
        boolean failSecondAdmission;
        boolean nullObservation;
        boolean duplicateRuns;
        int linkReads;
        int providerCalls;
        int workflowReads;
        final java.util.Set<RepositoryTarget> selfAdmitted = new java.util.HashSet<>();
        final List<org.pipesor.ingestion.github.application.ports.RepositoryAdmissions.Revocation> revocations = new ArrayList<>();
        java.util.Set<String> revokedOnNextCall = java.util.Set.of();
        final org.pipesor.ingestion.github.application.ports.RepositoryAdmissions repositoryAdmissions =
                new org.pipesor.ingestion.github.application.ports.RepositoryAdmissions() {
            @Override public boolean admitByAdministrator(Admission admission) {
                var target = new RepositoryTarget(admission.repositoryId(), admission.installationId());
                // FIRST and SECOND already hold active operator admissions (unless denied): never rebound.
                boolean active = (target.equals(FIRST) || target.equals(SECOND)) && !target.equals(deniedAdmission);
                return !active && selfAdmitted.add(target);
            }
            @Override public List<String> revokeAbsent(Revocation revocation) {
                revocations.add(revocation);
                return List.copyOf(revokedOnNextCall);
            }
        };
        List<Collaborator> collaboratorList = List.of();
        boolean moreCollaborators;
        int collaboratorReads;
        CollaboratorsRequest lastCollaborators;
        Set<String> linkedUsers = Set.of();
        Set<PipesorConnections.Principal> extraLinked = Set.of();
        final List<PipesorConnections.Request> connectionReads = new ArrayList<>();
        String admittedTenant = "tenant-a";
        final GitHubQueryHandler handler = new GitHubQueryHandler(this::link, this::admission, repositoryAdmissions, this,
                this::connections, CLOCK);

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
            return observed("admission", request, request.identity().context().tenantId().equals(admittedTenant)
                    && (knownAccountInstallation && !request.repository().equals(deniedAdmission)
                    || selfAdmitted.contains(request.repository())));
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
        public Observation<Identity, List<InstallationRepositories>> installations(Identity request) {
            providerCalls++;
            var grouped = new java.util.TreeMap<String, List<RepositoryFacts>>();
            for (var value : facts.values()) {
                if (value == null) { return observed("repositories", request, java.util.Arrays.asList((InstallationRepositories) null)); }
                grouped.computeIfAbsent(value.target().installationId(), key -> new ArrayList<>()).add(value);
            }
            // Same read boundary as repositories(): discovery now reads installations.
            return observed("repositories", request, grouped.values().stream().map(values -> new InstallationRepositories(
                    values.get(0).target().installationId(), values.get(0).accountId(), "login-" + values.get(0).accountId(),
                    true, values)).toList());
        }

        @Override
        public Observation<RepositoryRequest, List<WorkflowMetadata>> workflows(RepositoryRequest request) {
            workflowReads++;
            return observed("workflows", request, workflowList != null ? workflowList : List.of(metadata(request.repository())));
        }

        @Override
        public Observation<WorkflowRequest, WorkflowMetadata> workflow(WorkflowRequest request) {
            workflowReads++;
            return observed("workflow", request, metadata(request.repository().repository()));
        }

        @Override
        public Observation<RunsRequest, RunPage> runs(RunsRequest request) {
            workflowReads++;
            var run = new WorkflowRun("7001", 2, "failure", "main", "a".repeat(40), NOW, java.net.URI.create("https://github.com/x/y/actions/runs/7001"));
            return observed("runs", request, new RunPage(duplicateRuns ? List.of(run, run) : List.of(run), true));
        }

        @Override
        public Observation<CollaboratorsRequest, CollaboratorPage> collaborators(CollaboratorsRequest request) {
            collaboratorReads++;
            lastCollaborators = request;
            return observed("collaborators", request, new CollaboratorPage(collaboratorList, moreCollaborators));
        }

        Observation<PipesorConnections.Request, Set<PipesorConnections.Principal>> connections(PipesorConnections.Request request) {
            connectionReads.add(request);
            var linked = new java.util.HashSet<>(extraLinked);
            request.principals().stream().filter(principal -> linkedUsers.contains(principal.githubUserId())).forEach(linked::add);
            return observed("connections", request, Set.copyOf(linked));
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
