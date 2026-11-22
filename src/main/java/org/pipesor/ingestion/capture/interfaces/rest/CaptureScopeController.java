package org.pipesor.ingestion.capture.interfaces.rest;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.ports.CaptureWorkflowScopes;
import org.pipesor.ingestion.capture.application.ports.CaptureWorkflowScopes.Status;
import org.pipesor.ingestion.shared.security.PlatformSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ADR-023: Pipeline sets the capture status of a registered workflow; route rule restricts the caller. */
@RestController
public final class CaptureScopeController {
    public static final String PATH = "/internal/v1/capture/workflow-scopes/{repositoryId}/{workflowId}";
    private static final Logger LOG = LoggerFactory.getLogger(CaptureScopeController.class);

    public record SyncRequest(UUID requestId, String correlationId, UUID pipelineId, String monitoringStatus) { }

    private final CaptureWorkflowScopes scopes;
    private final UUID tenant;
    private final Clock clock;

    public CaptureScopeController(CaptureWorkflowScopes scopes, PlatformSecurityProperties platform, Clock clock) {
        this.scopes = Objects.requireNonNull(scopes);
        this.tenant = Objects.requireNonNull(platform.tenantId());
        this.clock = Objects.requireNonNull(clock);
    }

    @PutMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sync(@PathVariable String repositoryId, @PathVariable String workflowId,
            @RequestBody SyncRequest body) {
        if (body == null || body.requestId() == null || body.pipelineId() == null || body.correlationId() == null
                || !body.correlationId().matches("[A-Za-z0-9._:-]{1,128}") || !repositoryId.matches("[1-9][0-9]{0,19}")
                || !workflowId.matches("[1-9][0-9]{0,19}") || !"ACTIVE".equals(body.monitoringStatus())
                && !"PAUSED".equals(body.monitoringStatus())) {
            return problem(400, "INVALID_REQUEST");
        }
        try {
            var synced = scopes.sync(new CaptureWorkflowScopes.Sync(tenant, repositoryId, workflowId, body.pipelineId(),
                    Status.valueOf(body.monitoringStatus()), clock.instant()));
            LOG.info("Capture scope synced: status={} revision={} correlation={}", synced.status(), synced.revision(),
                    body.correlationId());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("repositoryId", synced.repositoryId(), "workflowId", synced.workflowId(),
                            "pipelineId", synced.pipelineId().toString(), "status", synced.status().name(),
                            "scopeRevision", synced.scopeRevision(), "revision", synced.revision()));
        } catch (CaptureWorkflowScopes.NotAdmitted notAdmitted) {
            LOG.warn("Capture scope refused: repository not admitted, correlation={}", body.correlationId());
            return problem(404, "REPOSITORY_NOT_ADMITTED");
        } catch (CaptureWorkflowScopes.PipelineMismatch mismatch) {
            LOG.warn("Capture scope refused: bound to another pipeline, correlation={}", body.correlationId());
            return problem(409, "PIPELINE_MISMATCH");
        } catch (RuntimeException unavailable) {
            LOG.error("Capture scope sync failed: {} correlation={}", unavailable.getClass().getName(), body.correlationId());
            return problem(503, "CAPTURE_SCOPE_UNAVAILABLE");
        }
    }

    private static ResponseEntity<Map<String, Object>> problem(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of("type", "about:blank", "status", status, "code", code));
    }
}
