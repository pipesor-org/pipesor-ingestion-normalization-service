package org.pipesor.ingestion.github.application.queryhandlers;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.application.ports.InstallationAdmission;
import org.pipesor.ingestion.github.application.ports.Observation;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub;
import org.pipesor.ingestion.github.application.queries.*;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;

import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.*;

public final class GitHubQueryHandler implements GitHubQueries {
    private final CurrentGitHubLink links;
    private final InstallationAdmission admission;
    private final ReadOnlyGitHub github;
    private final Clock clock;

    public GitHubQueryHandler(CurrentGitHubLink links, InstallationAdmission admission,
                              ReadOnlyGitHub github, Clock clock) {
        this.links = Objects.requireNonNull(links);
        this.admission = Objects.requireNonNull(admission);
        this.github = Objects.requireNonNull(github);
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
        var repositories = read(identity, () -> github.repositories(identity));
        var visible = new ArrayList<GitHubViews.Repository>();
        var seen = new HashSet<RepositoryTarget>();
        for (var facts : repositories) {
            if (facts == null || !seen.add(facts.target())) { throw failure(UNAVAILABLE); }
            if (admitted(identity, facts) && facts.readable()) { visible.add(view(facts)); }
        }
        recheck(identity);
        return new GitHubViews.Repositories(query.context(), identity.link(), clock.instant(), visible);
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
