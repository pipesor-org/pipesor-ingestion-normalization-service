package org.pipesor.ingestion.github.application.queryhandlers;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.application.ports.InstallationAdmission;
import org.pipesor.ingestion.github.application.ports.PipesorConnections;
import org.pipesor.ingestion.github.application.ports.RepositoryAdmissions;
import org.pipesor.ingestion.github.application.ports.Observation;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub;
import org.pipesor.ingestion.github.application.queries.*;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.shared.security.PlatformIds;

import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.*;

public final class GitHubQueryHandler implements GitHubQueries {
    private final CurrentGitHubLink links;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GitHubQueryHandler.class);
    private final InstallationAdmission admission;
    private final RepositoryAdmissions admissions;
    private final ReadOnlyGitHub github;
    private final PipesorConnections connections;
    private final Clock clock;

    public GitHubQueryHandler(CurrentGitHubLink links, InstallationAdmission admission, RepositoryAdmissions admissions,
                              ReadOnlyGitHub github, PipesorConnections connections, Clock clock) {
        this.links = Objects.requireNonNull(links);
        this.admission = Objects.requireNonNull(admission);
        this.admissions = Objects.requireNonNull(admissions);
        this.github = Objects.requireNonNull(github);
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public GitHubViews.Access verifyAccess(VerifyAccessQuery query) {
        var identity = begin(query.context());
        var verified = new HashMap<RepositoryTarget, GitHubViews.RepositoryAccess>();
        for (var target : query.repositories()) {
            try {
                var facts = repository(identity, target);
                verified.put(target, accessFacts(identity, facts));
            } catch (GitHubAccessFailure failure) {
                if (failure.reason() != DENIED) { throw failure; }
                verified.put(target, new GitHubViews.RepositoryAccess(false, false, false));
            }
        }
        recheck(identity);
        return access(identity, verified);
    }

    @Override
    public GitHubViews.Repositories discoverRepositories(DiscoverRepositoriesQuery query) {
        var identity = begin(query.context());
        var installations = reconcile(identity);
        var visible = new ArrayList<GitHubViews.Repository>();
        for (var installation : installations) {
            for (var repository : installation.repositories()) {
                if (repository.admitted() && repository.facts().readable()) { visible.add(view(repository.facts())); }
            }
        }
        recheck(identity);
        return new GitHubViews.Repositories(query.context(), identity.link(), clock.instant(), visible);
    }

    @Override
    public GitHubViews.Installations listInstallations(DiscoverRepositoriesQuery query) {
        var identity = begin(query.context());
        var views = reconcile(identity).stream().map(installation -> new GitHubViews.Installation(
                installation.source().installationId(), installation.source().accountId(), installation.source().accountLogin(),
                installation.source().active(), installation.repositories().stream().map(repository ->
                        new GitHubViews.RepositoryAdmission(repository.facts().target().repositoryId(), repository.facts().fullName(),
                                repository.admitted(), repository.facts().administrator())).toList())).toList();
        recheck(identity);
        return new GitHubViews.Installations(query.context(), identity.link(), clock.instant(), views);
    }

    private record ReconciledRepository(RepositoryFacts facts, boolean admitted) {}
    private record ReconciledInstallation(ReadOnlyGitHub.InstallationRepositories source, List<ReconciledRepository> repositories) {}

    /**
     * ADR-024: from facts just read with the subject's token, admit repositories the subject administers in a pipesor
     * installation and revoke admissions whose repositories the installation no longer lists. Browser data plays no part.
     */
    private List<ReconciledInstallation> reconcile(ReadOnlyGitHub.Identity identity) {
        var installations = read(identity, () -> github.installations(identity));
        var tenant = identity.context().tenantId();
        var subject = identity.context().subjectRef();
        var seen = new HashSet<RepositoryTarget>();
        var result = new ArrayList<ReconciledInstallation>();
        for (var installation : installations) {
            if (installation == null) { throw failure(UNAVAILABLE); }
            var repositories = new ArrayList<ReconciledRepository>();
            var listed = new HashSet<String>();
            for (var facts : installation.repositories()) {
                if (facts == null || !seen.add(facts.target())
                        || !installation.installationId().equals(facts.target().installationId())) { throw failure(UNAVAILABLE); }
                listed.add(facts.target().repositoryId());
                boolean admitted = admitted(identity, facts);
                if (!admitted && facts.readable() && facts.administrator()) {
                    admitted = admissions.admitByAdministrator(new RepositoryAdmissions.Admission(tenant, facts.accountId(),
                            facts.target().installationId(), facts.target().repositoryId(), subject, clock.instant()));
                    if (admitted) {
                        LOG.info("repository_admitted source=repository_administrator installation={} repository={} subject={}",
                                facts.target().installationId(), facts.target().repositoryId(), subject);
                    }
                }
                repositories.add(new ReconciledRepository(facts, admitted));
            }
            if (installation.active()) {
                for (var revoked : admissions.revokeAbsent(new RepositoryAdmissions.Revocation(tenant,
                        installation.installationId(), listed, subject, clock.instant()))) {
                    LOG.info("repository_admission_revoked reason=not_listed_by_installation installation={} repository={} subject={}",
                            installation.installationId(), revoked, subject);
                }
            }
            result.add(new ReconciledInstallation(installation, repositories));
        }
        return result;
    }

    @Override
    public GitHubViews.Workflows discoverWorkflows(DiscoverWorkflowsQuery query) {
        var identity = begin(query.discovery().context());
        var repository = requireRepository(identity, query.repository());
        var request = new ReadOnlyGitHub.RepositoryRequest(identity, query.repository());
        var workflows = read(request, () -> github.workflows(request));
        var ids = new HashSet<String>();
        for (var workflow : workflows) {
            if (workflow == null || !query.repository().equals(workflow.repository()) || !ids.add(workflow.workflowId())) {
                throw failure(UNAVAILABLE);
            }
        }
        recheck(identity);
        return new GitHubViews.Workflows(identity.context(), identity.link(), clock.instant(), view(repository), workflows);
    }

    @Override
    public GitHubViews.RepositoryPage discoverRepositoryPage(DiscoverRepositoryPageQuery query) {
        var all = discoverRepositories(query.discovery());
        var page = DiscoveryPaging.slice(all.repositories(), GitHubViews.Repository::fullName,
                repository -> repository.target().repositoryId(), repository -> List.of(repository.fullName()), query.page());
        return new GitHubViews.RepositoryPage(all.context(), all.link(), all.observedAt(), page.items(), page.next());
    }

    @Override
    public GitHubViews.WorkflowPage discoverWorkflowPage(DiscoverWorkflowPageQuery query) {
        var all = discoverWorkflows(query.discovery());
        var page = DiscoveryPaging.slice(all.workflows(), WorkflowMetadata::name, WorkflowMetadata::workflowId,
                workflow -> List.of(workflow.name(), workflow.file()), query.page());
        return new GitHubViews.WorkflowPage(all.context(), all.link(), all.observedAt(), all.repository(), page.items(), page.next());
    }

    @Override
    public GitHubViews.Selection selectWorkflow(SelectWorkflowQuery query) {
        var identity = begin(query.context());
        var repository = requireRepository(identity, query.repository());
        var request = new ReadOnlyGitHub.WorkflowRequest(
                new ReadOnlyGitHub.RepositoryRequest(identity, query.repository()), query.workflowId());
        var workflow = read(request, () -> github.workflow(request));
        if (!query.repository().equals(workflow.repository()) || !query.workflowId().equals(workflow.workflowId())) {
            throw failure(UNAVAILABLE);
        }
        recheck(identity);
        return new GitHubViews.Selection(access(identity, Map.of(query.repository(),
                new GitHubViews.RepositoryAccess(true, true, repository.administrator(), repository.accountId()))), view(repository), workflow);
    }

    @Override
    public GitHubViews.Runs listRuns(ListRunsQuery query) {
        var identity = begin(query.context());
        var repository = requireRepository(identity, query.repository());
        var request = new ReadOnlyGitHub.RunsRequest(new ReadOnlyGitHub.WorkflowRequest(
                new ReadOnlyGitHub.RepositoryRequest(identity, query.repository()), query.workflowId()), query.conclusion(),
                query.page(), query.pageSize());
        var page = read(request, () -> github.runs(request));
        var ids = new HashSet<String>();
        for (var run : page.runs()) {
            if (run == null || !ids.add(run.runId())) { throw failure(UNAVAILABLE); }
        }
        recheck(identity);
        return new GitHubViews.Runs(identity.context(), identity.link(), clock.instant(), view(repository), page.runs(), page.more());
    }

    /**
     * ADR-028: only a current administrator of an admitted, readable repository (GitHub's {@code permissions.admin} in
     * the user's installation listing) sees its collaborators. The Pipesor link state of the whole page is read in one
     * batch after the GitHub read, never inside it.
     */
    @Override
    public GitHubViews.Collaborators listCollaborators(ListCollaboratorsQuery query) {
        var tenant = tenant(query.context());
        var identity = begin(query.context());
        var repository = repository(identity, query.repository());
        var access = accessFacts(identity, repository);
        if (!access.readable() || !access.administrator()) { throw failure(DENIED); }
        var request = new ReadOnlyGitHub.CollaboratorsRequest(new ReadOnlyGitHub.RepositoryRequest(identity, query.repository()),
                query.page(), query.pageSize());
        var page = read(request, () -> github.collaborators(request));
        if (page.collaborators().size() > query.pageSize()) { throw failure(UNAVAILABLE); }
        var principals = new LinkedHashMap<String, PipesorConnections.Principal>();
        for (var collaborator : page.collaborators()) {
            if (collaborator == null || collaborator.role() == null || collaborator.login() == null
                    || principals.put(collaborator.githubUserId(), principal(collaborator.githubUserId())) != null) {
                throw failure(UNAVAILABLE);
            }
        }
        var lookup = new PipesorConnections.Request(tenant, Set.copyOf(principals.values()));
        Set<PipesorConnections.Principal> linked = lookup.principals().isEmpty() ? Set.of() : read(lookup, () -> connections.active(lookup));
        if (!lookup.principals().containsAll(linked)) { throw failure(UNAVAILABLE); }
        var views = new ArrayList<GitHubViews.Collaborator>();
        for (var collaborator : page.collaborators()) {
            var principal = principals.get(collaborator.githubUserId());
            views.add(new GitHubViews.Collaborator(collaborator.githubUserId(), collaborator.login(), collaborator.avatarUrl(),
                    collaborator.role(), tenant + ":" + principal.objectId(), linked.contains(principal)));
        }
        recheck(identity);
        return new GitHubViews.Collaborators(identity.context(), identity.link(), clock.instant(), view(repository), views, page.more());
    }

    /** The caller's tenant in canonical lower-case UUID form, the only form principal references are issued in. */
    private UUID tenant(AccessContext context) {
        try {
            var tenant = UUID.fromString(context.tenantId());
            if (tenant.toString().equals(context.tenantId())) { return tenant; }
        } catch (IllegalArgumentException invalid) { /* not a UUID */ }
        throw failure(UNAVAILABLE);
    }

    /** The platform principal of a GitHub user ID (identity contract v1); a non-canonical ID is a provider inconsistency. */
    private PipesorConnections.Principal principal(String githubUserId) {
        if (githubUserId == null || !githubUserId.matches("[1-9][0-9]{0,18}")) { throw failure(UNAVAILABLE); }
        try { return new PipesorConnections.Principal(PlatformIds.human(Long.parseLong(githubUserId)), githubUserId); }
        catch (IllegalArgumentException invalid) { throw failure(UNAVAILABLE); }
    }

    private ReadOnlyGitHub.Identity begin(AccessContext context) {
        var identity = identity(context);
        var actualUser = read(identity, () -> github.currentUser(identity));
        if (actualUser.isBlank()) { throw failure(UNAVAILABLE); }
        if (!identity.link().githubUserId().equals(actualUser)) { throw failure(CONNECTION_REQUIRED); }
        return identity;
    }

    private ReadOnlyGitHub.Identity identity(AccessContext context) {
        var link = read(context, () -> links.read(context)).orElseThrow(() -> failure(CONNECTION_REQUIRED));
        if (!context.tenantId().equals(link.tenantId()) || !context.subjectRef().equals(link.subjectRef())) {
            throw failure(UNAVAILABLE);
        }
        return new ReadOnlyGitHub.Identity(context, link);
    }

    private void recheck(ReadOnlyGitHub.Identity previous) {
        if (!previous.equals(identity(previous.context()))) { throw failure(CONNECTION_REQUIRED); }
    }

    private RepositoryFacts requireRepository(ReadOnlyGitHub.Identity identity, RepositoryTarget target) {
        var facts = repository(identity, target);
        if (!accessFacts(identity, facts).readable()) { throw failure(DENIED); }
        return facts;
    }

    private RepositoryFacts repository(ReadOnlyGitHub.Identity identity, RepositoryTarget target) {
        var request = new ReadOnlyGitHub.RepositoryRequest(identity, target);
        var facts = read(request, () -> github.repository(request));
        if (!target.equals(facts.target())) { throw failure(UNAVAILABLE); }
        return facts;
    }

    private GitHubViews.RepositoryAccess accessFacts(ReadOnlyGitHub.Identity identity, RepositoryFacts facts) {
        boolean admitted = admitted(identity, facts);
        boolean readable = admitted && facts.readable();
        boolean administrator = readable && facts.administrator();
        return new GitHubViews.RepositoryAccess(admitted, readable, administrator, readable ? facts.accountId() : null);
    }

    private boolean admitted(ReadOnlyGitHub.Identity identity, RepositoryFacts facts) {
        var request = new InstallationAdmission.Request(identity, facts.accountId(), facts.target());
        return read(request, () -> admission.read(request));
    }

    private GitHubViews.Access access(ReadOnlyGitHub.Identity identity,
                                      Map<RepositoryTarget, GitHubViews.RepositoryAccess> targets) {
        return new GitHubViews.Access(identity.context(), identity.link(), targets, clock.instant());
    }

    private GitHubViews.Repository view(RepositoryFacts facts) {
        return new GitHubViews.Repository(facts.target(), facts.fullName(), facts.defaultBranch());
    }

    private <Q, T> T read(Q request, Supplier<Observation<Q, T>> source) {
        var start = clock.instant();
        Observation<Q, T> observed;
        try { observed = source.get(); }
        catch (GitHubAccessFailure expected) { throw failure(expected.reason()); }
        catch (RuntimeException failure) { throw failure(UNAVAILABLE); }
        var end = clock.instant();
        if (observed == null || !request.equals(observed.request()) || observed.value() == null
                || observed.observedAt() == null || observed.observedAt().isBefore(start)
                || observed.observedAt().isAfter(end) || end.isBefore(start)) {
            throw failure(UNAVAILABLE);
        }
        return observed.value();
    }

    private GitHubAccessFailure failure(GitHubAccessFailure.Reason reason) { return new GitHubAccessFailure(reason); }
}
