package org.pipesor.ingestion.capture.infrastructure.persistence.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.pipesor.ingestion.capture.application.ports.CaptureWorkflowScopes;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.infrastructure.configuration.CaptureAuthority;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.Authorization;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.HookContext;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.Source;
import org.pipesor.ingestion.github.application.ports.Observation;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-023 runtime authority: operator repository admissions and Pipeline-driven workflow scopes in
 * the owned {@code ingestion} schema. Every decision reads current rows; failures never fall back
 * to a cached or permissive answer.
 */
public final class JdbcCaptureAuthority implements CaptureAuthority, CaptureWorkflowScopes,
        org.pipesor.ingestion.github.application.ports.RepositoryAdmissions {
    private static final String SCOPE_COLUMNS = """
            capture_domain, tenant_id, app_id, account_id, installation_id, repository_id, workflow_id,
            allowed_actions, extract(epoch from replay_window)::bigint as replay_seconds, scope_revision""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String appId;
    private final String captureDomain;
    private final Duration defaultReplay;
    private final Clock clock;

    public JdbcCaptureAuthority(JdbcTemplate jdbc, TransactionTemplate transactions, String appId, String captureDomain,
            Duration defaultReplay, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.appId = CaptureScope.external(appId);
        this.captureDomain = CaptureScope.safe(captureDomain);
        this.defaultReplay = Objects.requireNonNull(defaultReplay);
        if (defaultReplay.isZero() || defaultReplay.isNegative()) throw new IllegalArgumentException("Positive replay window required");
        this.clock = Objects.requireNonNull(clock);
    }

    private record Row(CaptureScope scope, Set<String> actions, Duration replay) { }

    @Override public void validate() {
        try {
            jdbc.queryForObject("select count(*) from ingestion.capture_workflow_scopes where false", Long.class);
        } catch (RuntimeException unavailable) {
            throw captureUnavailable();
        }
    }

    @Override public Decision verify(CaptureScope expected) {
        try {
            return activeExact(expected).isPresent() ? Decision.AUTHORIZED : Decision.CHANGED_OR_LOST;
        } catch (RuntimeException unavailable) {
            throw captureUnavailable();
        }
    }

    @Override public Decision verifyProtectedEvidenceScope(HookContext trustedRoute, WorkflowObservation.CaptureContext capture,
            WorkflowObservation.Pipeline pipeline) {
        try {
            if (!appId.equals(trustedRoute.appId())) return Decision.UNAVAILABLE;
            if (!trustedRoute.tenantId().equals(capture.tenantId())) return Decision.CHANGED_OR_LOST;
            var expected = new CaptureScope(trustedRoute.captureDomain(), trustedRoute.tenantId(), trustedRoute.appId(),
                    capture.githubAccountId(), capture.githubInstallationId(), pipeline.githubRepositoryId(),
                    pipeline.githubWorkflowId(), capture.scopeRevision());
            return activeExact(expected).filter(row -> row.actions().contains("completed")).isPresent()
                    ? Decision.AUTHORIZED : Decision.CHANGED_OR_LOST;
        } catch (RuntimeException unavailable) {
            return Decision.UNAVAILABLE;
        }
    }

    @Override public Optional<CaptureScope> completedScope(String domain, UUID tenant, String repositoryId, String workflowId) {
        try {
            CaptureScope.external(repositoryId);
            CaptureScope.external(workflowId);
        } catch (CaptureFailure invalid) {
            return Optional.empty();
        }
        try {
            return jdbc.query("select " + SCOPE_COLUMNS + """
                     from ingestion.capture_workflow_scopes
                    where capture_domain = ? and tenant_id = ? and app_id = ? and repository_id = ? and workflow_id = ?
                      and status = 'ACTIVE' and 'completed' = any(allowed_actions)
                      and exists (select 1 from ingestion.repository_admissions a where a.tenant_id = capture_workflow_scopes.tenant_id
                                   and a.app_id = capture_workflow_scopes.app_id and a.repository_id = capture_workflow_scopes.repository_id
                                   and a.revoked_at is null)""",
                    this::row, domain, tenant, appId, repositoryId, workflowId).stream().findFirst().map(Row::scope);
        } catch (RuntimeException unavailable) {
            throw captureUnavailable();
        }
    }

    @Override public Instant completedReplayUntil(CaptureScope scope) {
        Optional<Row> row;
        try {
            row = activeExact(scope).filter(candidate -> candidate.actions().contains("completed"));
        } catch (RuntimeException unavailable) {
            throw captureUnavailable();
        }
        return row.map(found -> clock.instant().plus(found.replay()))
                .orElseThrow(() -> new CaptureFailure(CaptureFailure.Reason.SCOPE_CHANGED));
    }

    public boolean admitted(UUID tenant, String accountId, RepositoryTarget repository) {
        try {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(repository);
            return admission(tenant, accountId, repository.installationId(), repository.repositoryId());
        } catch (RuntimeException unavailable) {
            throw accessUnavailable();
        }
    }

    @Override public Observation<org.pipesor.ingestion.github.application.ports.InstallationAdmission.Request, Boolean> read(
            org.pipesor.ingestion.github.application.ports.InstallationAdmission.Request request) {
        try {
            Objects.requireNonNull(request);
            var identity = Objects.requireNonNull(request.identity());
            var context = identity.context();
            var link = identity.link();
            var tenant = tenant(context.tenantId());
            if (!tenant.equals(tenant(link.tenantId())) || !context.subjectRef().equals(link.subjectRef()) || link.generationId() == null) {
                throw new IllegalArgumentException();
            }
            CaptureScope.external(request.accountId());
            CaptureScope.external(request.repository().repositoryId());
            CaptureScope.external(request.repository().installationId());
            boolean granted = admission(tenant, request.accountId(), request.repository().installationId(),
                    request.repository().repositoryId());
            return new Observation<>(request, granted, clock.instant());
        } catch (RuntimeException unavailable) {
            throw accessUnavailable();
        }
    }

    @Override public Observation<org.pipesor.ingestion.github.application.ports.GitHubRepositorySelection.Request,
            Map<String, Optional<RepositoryTarget>>> resolve(
            org.pipesor.ingestion.github.application.ports.GitHubRepositorySelection.Request request) {
        try {
            Objects.requireNonNull(request);
            var tenant = tenant(request.context().tenantId());
            var result = new HashMap<String, Optional<RepositoryTarget>>();
            for (var id : request.repositoryIds()) {
                CaptureScope.external(id);
                var installation = jdbc.query("""
                        select installation_id from ingestion.repository_admissions
                         where tenant_id = ? and app_id = ? and repository_id = ? and revoked_at is null""",
                        (rs, i) -> rs.getString(1), tenant, appId, id).stream().findFirst();
                result.put(id, installation.map(found -> new RepositoryTarget(id, found)));
            }
            return new Observation<>(request, Map.copyOf(result), clock.instant());
        } catch (RuntimeException unavailable) {
            throw accessUnavailable();
        }
    }

    @Override public Function<Source, Authorization> webhook(HookContext hook) {
        Objects.requireNonNull(hook);
        if (!appId.equals(hook.appId())) throw new IllegalArgumentException("Webhook authority binding required");
        return source -> {
            try {
                Objects.requireNonNull(source);
                CaptureScope.external(source.installationId());
                CaptureScope.external(source.accountId());
                CaptureScope.external(source.repositoryId());
                CaptureScope.external(source.workflowId());
                CaptureScope.external(source.runId());
                if (source.runAttempt() < 1) throw new IllegalArgumentException();
                return jdbc.query("select " + SCOPE_COLUMNS + """
                         from ingestion.capture_workflow_scopes
                        where capture_domain = ? and tenant_id = ? and app_id = ? and account_id = ? and installation_id = ?
                          and repository_id = ? and workflow_id = ? and status = 'ACTIVE' and ? = any(allowed_actions)""",
                        this::row, hook.captureDomain(), hook.tenantId(), hook.appId(), source.accountId(), source.installationId(),
                        source.repositoryId(), source.workflowId(), source.action()).stream().findFirst()
                        .map(row -> new Authorization(row.scope(), clock.instant().plus(row.replay()))).orElse(null);
            } catch (RuntimeException unavailable) {
                throw captureUnavailable();
            }
        };
    }

    @Override public Synced sync(Sync request) {
        Objects.requireNonNull(request);
        CaptureScope.external(request.repositoryId());
        CaptureScope.external(request.workflowId());
        return transactions.execute(status -> {
            var admission = jdbc.query("""
                    select account_id, installation_id from ingestion.repository_admissions
                     where tenant_id = ? and app_id = ? and repository_id = ? and revoked_at is null for share""",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                    request.tenantId(), appId, request.repositoryId()).stream().findFirst().orElseThrow(NotAdmitted::new);
            var synced = jdbc.query("""
                    insert into ingestion.capture_workflow_scopes as current (capture_domain, tenant_id, app_id, account_id,
                      installation_id, repository_id, workflow_id, pipeline_id, status, allowed_actions, replay_window,
                      revision, scope_revision, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, array['completed'], make_interval(secs => ?), 1, 'scope-r1', ?)
                    on conflict (capture_domain, tenant_id, app_id, repository_id, workflow_id) do update set
                      pipeline_id = excluded.pipeline_id, status = excluded.status, account_id = excluded.account_id,
                      installation_id = excluded.installation_id, updated_at = excluded.updated_at,
                      revision = case when (current.status, current.account_id, current.installation_id)
                          is distinct from (excluded.status, excluded.account_id, excluded.installation_id)
                        then current.revision + 1 else current.revision end,
                      scope_revision = case when (current.status, current.account_id, current.installation_id)
                          is distinct from (excluded.status, excluded.account_id, excluded.installation_id)
                        then 'scope-r' || (current.revision + 1) else current.scope_revision end
                    where current.pipeline_id is null or current.pipeline_id = excluded.pipeline_id
                    returning repository_id, workflow_id, pipeline_id, status, revision, scope_revision""",
                    (rs, i) -> new Synced(rs.getString("repository_id"), rs.getString("workflow_id"),
                            rs.getObject("pipeline_id", UUID.class), CaptureWorkflowScopes.Status.valueOf(rs.getString("status")),
                            rs.getLong("revision"), rs.getString("scope_revision")),
                    captureDomain, request.tenantId(), appId, admission[0], admission[1], request.repositoryId(),
                    request.workflowId(), request.pipelineId(), request.status().name(), defaultReplay.toSeconds(),
                    Timestamp.from(request.at()));
            return synced.stream().findFirst().orElseThrow(PipelineMismatch::new);
        });
    }

    /** Operator import (ADR-023): idempotent upsert of reviewed admissions and legacy scopes. */
    public void importAdmission(UUID tenant, String account, String installation, String repository, String admittedBy, Instant at) {
        jdbc.update("""
                insert into ingestion.repository_admissions (tenant_id, app_id, account_id, installation_id, repository_id,
                  admitted_at, admitted_by) values (?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, repository_id) do update set account_id = excluded.account_id,
                  installation_id = excluded.installation_id, app_id = excluded.app_id, source = 'operator',
                  revoked_at = null, revoked_by = null""",
                tenant, appId, CaptureScope.external(account), CaptureScope.external(installation),
                CaptureScope.external(repository), Timestamp.from(at), admittedBy);
    }

    /**
     * ADR-024: the subject administers the repository in a pipesor installation, as just read from GitHub. Creates a new
     * admission or reactivates a revoked one; an active admission bound to another account or installation is never
     * rebound here (that stays an operator decision), so the call then grants nothing.
     */
    @Override public boolean admitByAdministrator(Admission admission) {
        Objects.requireNonNull(admission);
        try {
            var granted = jdbc.query("""
                    insert into ingestion.repository_admissions as current (tenant_id, app_id, account_id, installation_id,
                      repository_id, admitted_at, admitted_by, source)
                    values (?, ?, ?, ?, ?, ?, ?, 'repository_administrator')
                    on conflict (tenant_id, repository_id) do update set
                      account_id = excluded.account_id, installation_id = excluded.installation_id, app_id = excluded.app_id,
                      admitted_at = excluded.admitted_at, admitted_by = excluded.admitted_by, source = excluded.source,
                      revoked_at = null, revoked_by = null
                    where current.revoked_at is not null
                    returning repository_id""", (rs, i) -> rs.getString(1),
                    tenant(admission.tenantId()), appId, CaptureScope.external(admission.accountId()),
                    CaptureScope.external(admission.installationId()), CaptureScope.external(admission.repositoryId()),
                    Timestamp.from(admission.at()), admission.subjectRef());
            return !granted.isEmpty();
        } catch (RuntimeException unavailable) {
            throw accessUnavailable();
        }
    }

    /** ADR-024: GitHub no longer lists these repositories in the installation (deselected or uninstalled). */
    @Override public List<String> revokeAbsent(Revocation revocation) {
        Objects.requireNonNull(revocation);
        try {
            CaptureScope.external(revocation.installationId());
            revocation.listedRepositoryIds().forEach(CaptureScope::external);
            return jdbc.query("""
                    update ingestion.repository_admissions set revoked_at = ?, revoked_by = ?
                     where tenant_id = ? and app_id = ? and installation_id = ? and revoked_at is null
                       and not (repository_id = any(?))
                    returning repository_id""", (rs, i) -> rs.getString(1),
                    Timestamp.from(revocation.at()), revocation.subjectRef(), tenant(revocation.tenantId()), appId,
                    revocation.installationId(), revocation.listedRepositoryIds().toArray(String[]::new));
        } catch (RuntimeException unavailable) {
            throw accessUnavailable();
        }
    }

    /** Legacy scopes keep their reviewed scope revision so previously captured evidence stays verifiable. */
    public void importScope(CaptureScope scope, Set<String> actions, Duration replay, Instant at) {
        jdbc.update("""
                insert into ingestion.capture_workflow_scopes (capture_domain, tenant_id, app_id, account_id, installation_id,
                  repository_id, workflow_id, pipeline_id, status, allowed_actions, replay_window, revision, scope_revision, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, null, 'ACTIVE', ?, make_interval(secs => ?), 1, ?, ?)
                on conflict (capture_domain, tenant_id, app_id, repository_id, workflow_id) do nothing""",
                scope.captureDomain(), scope.tenantId(), scope.appId(), scope.accountId(), scope.installationId(),
                scope.repositoryId(), scope.workflowId(), actions.stream().sorted().toArray(String[]::new),
                replay.toSeconds(), scope.scopeRevision(), Timestamp.from(at));
    }

    private Optional<Row> activeExact(CaptureScope expected) {
        return jdbc.query("select " + SCOPE_COLUMNS + """
                 from ingestion.capture_workflow_scopes
                where capture_domain = ? and tenant_id = ? and app_id = ? and account_id = ? and installation_id = ?
                  and repository_id = ? and workflow_id = ? and scope_revision = ? and status = 'ACTIVE'
                  and exists (select 1 from ingestion.repository_admissions a where a.tenant_id = capture_workflow_scopes.tenant_id
                               and a.app_id = capture_workflow_scopes.app_id and a.repository_id = capture_workflow_scopes.repository_id
                               and a.revoked_at is null)""",
                this::row, expected.captureDomain(), expected.tenantId(), expected.appId(), expected.accountId(),
                expected.installationId(), expected.repositoryId(), expected.workflowId(), expected.scopeRevision())
                .stream().findFirst();
    }

    private boolean admission(UUID tenant, String account, String installation, String repository) {
        Long count = jdbc.queryForObject("""
                select count(*) from ingestion.repository_admissions
                 where tenant_id = ? and app_id = ? and account_id = ? and installation_id = ? and repository_id = ?
                   and revoked_at is null""",
                Long.class, tenant, appId, account, installation, repository);
        return count != null && count > 0;
    }

    private Row row(ResultSet rs, int index) throws SQLException {
        var scope = new CaptureScope(rs.getString("capture_domain"), rs.getObject("tenant_id", UUID.class), rs.getString("app_id"),
                rs.getString("account_id"), rs.getString("installation_id"), rs.getString("repository_id"),
                rs.getString("workflow_id"), rs.getString("scope_revision"));
        var actions = Set.copyOf(Arrays.asList((String[]) rs.getArray("allowed_actions").getArray()));
        return new Row(scope, actions, Duration.ofSeconds(rs.getLong("replay_seconds")));
    }

    private static UUID tenant(String raw) {
        var value = UUID.fromString(raw);
        if (!value.toString().equalsIgnoreCase(raw) || value.equals(new UUID(0, 0))) throw new IllegalArgumentException();
        return value;
    }

    private static CaptureFailure captureUnavailable() { return new CaptureFailure(CaptureFailure.Reason.UNAVAILABLE); }

    private static GitHubAccessFailure accessUnavailable() { return new GitHubAccessFailure(GitHubAccessFailure.Reason.UNAVAILABLE); }

    @Override public String toString() { return "JdbcCaptureAuthority[configured]"; }
}
