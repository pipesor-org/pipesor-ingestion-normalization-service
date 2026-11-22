package org.pipesor.ingestion.normalization.interfaces.messaging.transform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler.Result;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure.Reason.SERIALIZATION_FAILED;

/** Wire representation only: no broker SDK, sending, durable receipt or retry identity allocation. */
public final class NormalizedMessagesTransformer {
    private final JsonMapper json = JsonMapper.builder().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    public List<Message> toMessages(Result result) {
        var observation = result.observation();
        var snapshot = new Snapshot("1.0", result.snapshotMessageId(), "execution.snapshot", "github_actions",
                observation.occurredAt(), observation.sourceUpdatedAt(), observation.correlationId(), observation.pipeline(),
                observation.execution(), new Commit(observation.commitSha()), observation.service(), observation.environment(),
                observation.stages(), observation.failure());
        var snapshotMessage = message(result.snapshotMessageId(), observation.correlationId(), "execution.snapshot", 1, snapshot);
        if (observation.evidence() == null) { return List.of(snapshotMessage); }
        var capture = observation.captureContext();
        var execution = observation.execution();
        var evidence = observation.evidence();
        var envelope = new EvidenceEnvelope(evidence.sourceLocator() == null ? "2.0" : "2.2", "protected_workflow_evidence", result.evidenceMessageId(),
                observation.occurredAt(), observation.sourceUpdatedAt(), observation.correlationId(), "github_actions",
                new CaptureContext(capture.tenantId(), capture.githubAccountId(), capture.githubInstallationId(), capture.scopeRevision()),
                new Pipeline(observation.pipeline().githubRepositoryId(), observation.pipeline().githubWorkflowId()),
                new Execution(execution.githubRunId(), execution.runAttempt(), execution.status(), execution.conclusion()),
                new Commit(observation.commitSha()), observation.service(), observation.environment(),
                new Evidence(evidence.stage(), evidence.job(), observation.failure().summary(), evidence.excerpt(), evidence.sequence(), evidence.sourceLocator()));
        return List.of(snapshotMessage, message(result.evidenceMessageId(), observation.correlationId(), "protected_workflow_evidence", 2, envelope));
    }

    private Message message(UUID id, String correlation, String eventType, int major, Object body) {
        try { return new Message(id, correlation, Map.of("eventType", eventType, "schemaMajor", major), json.writeValueAsBytes(body)); }
        catch (RuntimeException invalid) { throw new NormalizationFailure(SERIALIZATION_FAILED); }
    }

    public static final class Message {
        private final UUID messageId;
        private final String correlationId;
        private final Map<String, Object> applicationProperties;
        private final byte[] body;

        private Message(UUID messageId, String correlationId, Map<String, Object> properties, byte[] body) {
            this.messageId = messageId;
            this.correlationId = correlationId;
            this.applicationProperties = Map.copyOf(properties);
            this.body = body.clone();
        }
        public UUID messageId() { return messageId; }
        public String correlationId() { return correlationId; }
        public String contentType() { return "application/json; charset=utf-8"; }
        public Map<String, Object> applicationProperties() { return applicationProperties; }
        public byte[] body() { return body.clone(); }
        @Override public String toString() { return "NormalizedMessage[redacted]"; }
    }

    private record Snapshot(String schemaVersion, UUID eventId, String eventType, String source,
            Instant occurredAt, Instant sourceUpdatedAt, String correlationId, WorkflowObservation.Pipeline pipeline,
            WorkflowObservation.Execution execution, Commit commit, String service, String environment,
            List<WorkflowObservation.Stage> stages, WorkflowObservation.Failure failure) {}
    private record Commit(String sha) {}
    private record EvidenceEnvelope(String schema_version, String event_type, UUID message_id, Instant occurred_at,
            Instant source_updated_at, String correlation_id, String source, CaptureContext capture_context,
            Pipeline pipeline, Execution execution, Commit commit, String service, String environment, Evidence evidence) {}
    private record CaptureContext(UUID tenant_id, String github_account_id, String github_installation_id, String scope_revision) {}
    private record Pipeline(String github_repository_id, String github_workflow_id) {}
    private record Execution(String github_run_id, int run_attempt, String status, String conclusion) {}
    private record Evidence(WorkflowObservation.StageName stage, String job, String summary, String excerpt, Long sequence,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String source_locator) {}
}
