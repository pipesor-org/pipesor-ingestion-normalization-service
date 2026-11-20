package org.pipesor.ingestion.normalization.domain.model.valueobjects;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure.Reason.*;

/** Whitelisted internal observation. Construction validates data, never authenticates its supplier. */
public record WorkflowObservation(CaptureContext captureContext, Pipeline pipeline, Execution execution,
        String commitSha, String service, String environment, String correlationId, Instant occurredAt,
        Instant sourceUpdatedAt, List<Stage> stages, Failure failure, Evidence evidence) {
    public WorkflowObservation {
        if (captureContext == null || pipeline == null || execution == null) { fail(INVALID_CONTEXT); }
        if (commitSha == null || !commitSha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) { fail(INVALID_IDENTITY); }
        commitSha = commitSha.toLowerCase(Locale.ROOT);
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._:-]{1,128}")) { fail(INVALID_CONTEXT); }
        if (occurredAt == null || sourceUpdatedAt == null) { fail(INVALID_TIME); }
        if (stages == null || stages.stream().anyMatch(java.util.Objects::isNull)) { fail(INVALID_STAGE); }
        var seen = new HashSet<StageName>();
        for (var stage : stages) {
            if (!seen.add(stage.stage())) { fail(INVALID_STAGE); }
        }
        stages = stages.stream().sorted(Comparator.comparing(Stage::stage)).toList();
        if (execution.failed() != (failure != null)) { fail(INVALID_EVIDENCE); }
        if (execution.failed() && evidence == null) { fail(INVALID_EVIDENCE); }
        if (evidence != null && (!execution.failed() || evidence.stage() != failure.stage())) { fail(INVALID_EVIDENCE); }
    }

    public UUID pipelineId() { return LogicalIds.pipeline(pipeline.githubRepositoryId(), pipeline.githubWorkflowId()); }
    public UUID executionId() { return LogicalIds.execution(pipeline.githubRepositoryId(), execution.githubRunId(), execution.runAttempt()); }
    public UUID incidentId() { return execution.failed() ? LogicalIds.incident(executionId()) : null; }

    @Override public String toString() { return "WorkflowObservation[redacted]"; }

    public enum StageName { BUILD, TESTING, QUALITY_GATE, DEPLOYMENT, CONTAINER, INFRASTRUCTURE }

    public record CaptureContext(UUID tenantId, String githubAccountId, String githubInstallationId, String scopeRevision) {
        public CaptureContext {
            LogicalIds.required(tenantId);
            LogicalIds.external(githubAccountId);
            LogicalIds.external(githubInstallationId);
            if (scopeRevision == null || !scopeRevision.matches("[A-Za-z0-9._:-]{1,128}")) { fail(INVALID_CONTEXT); }
        }
        @Override public String toString() { return "CaptureContext[redacted]"; }
    }

    public record Pipeline(String githubRepositoryId, String githubWorkflowId) {
        public Pipeline {
            LogicalIds.external(githubRepositoryId);
            LogicalIds.external(githubWorkflowId);
        }
    }

    public record Execution(String githubRunId, int runAttempt, String branch, String status,
            String conclusion, Instant startedAt, Instant completedAt) {
        private static final Set<String> STATUSES = Set.of("queued", "in_progress", "completed", "waiting", "requested", "pending");
        private static final Set<String> CONCLUSIONS = Set.of("success", "failure", "neutral", "cancelled", "skipped",
                "timed_out", "action_required", "stale", "startup_failure");
        public Execution {
            LogicalIds.external(githubRunId);
            if (runAttempt < 1) { fail(INVALID_IDENTITY); }
            text(branch, INVALID_CONTEXT);
            if (status == null || !STATUSES.contains(status)
                    || ("completed".equals(status) && (conclusion == null || !CONCLUSIONS.contains(conclusion)))
                    || (!"completed".equals(status) && (conclusion != null || completedAt != null))) { fail(INVALID_STATUS); }
            ordered(startedAt, completedAt);
        }
        public boolean failed() { return "completed".equals(status) && Set.of("failure", "timed_out").contains(conclusion); }
        @Override public String toString() { return "Execution[redacted]"; }
    }

    public record Stage(StageName stage, String status, Instant startedAt, Instant completedAt) {
        public Stage {
            if (stage == null || status == null || !Set.of("queued", "in_progress", "completed", "skipped").contains(status)) { fail(INVALID_STAGE); }
            ordered(startedAt, completedAt);
        }
    }

    public record Failure(StageName stage, String summary, Instant detectedAt) {
        public Failure {
            if (stage == null) { fail(INVALID_STAGE); }
            text(summary, INVALID_EVIDENCE);
            if (detectedAt == null) { fail(INVALID_TIME); }
        }
        @Override public String toString() { return "Failure[redacted]"; }
    }

    public record Evidence(StageName stage, String job, String excerpt, Long sequence, String sourceLocator) {
        public Evidence(StageName stage, String job, String excerpt, Long sequence) {
            this(stage, job, excerpt, sequence, null);
        }
        public Evidence {
            if (stage == null) { fail(INVALID_STAGE); }
            if (sourceLocator != null && (job == null || !job.matches("[1-9][0-9]*") || !sourceLocator.matches(
                    "v2:job:" + job + ":member:[0-9]{1,9}_verify\\.txt:lines:([1-9][0-9]{0,8})-\\1:sha256:[0-9a-f]{64}"))) { fail(INVALID_EVIDENCE); }
            text(excerpt, INVALID_EVIDENCE);
            if (job != null && job.isBlank()) { fail(INVALID_EVIDENCE); }
            if (sequence != null && sequence < 0) { fail(INVALID_EVIDENCE); }
        }
        @Override public String toString() { return "Evidence[redacted]"; }
    }

    private static void ordered(Instant start, Instant end) {
        if (start != null && end != null && end.isBefore(start)) { fail(INVALID_TIME); }
    }

    private static void text(String value, NormalizationFailure.Reason reason) {
        if (value == null || value.isBlank()) { fail(reason); }
    }

    private static void fail(NormalizationFailure.Reason reason) { throw new NormalizationFailure(reason); }
}
