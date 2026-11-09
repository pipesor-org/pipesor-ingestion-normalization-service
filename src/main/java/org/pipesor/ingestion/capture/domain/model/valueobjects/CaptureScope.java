package org.pipesor.ingestion.capture.domain.model.valueobjects;

import java.util.UUID;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import static org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure.Reason.INVALID_INPUT;

/** Independently trusted machine scope; construction alone does not authenticate a source. */
public record CaptureScope(String captureDomain, UUID tenantId, String appId, String accountId,
        String installationId, String repositoryId, String workflowId, String scopeRevision) {
    public CaptureScope {
        safe(captureDomain); safe(scopeRevision);
        if (tenantId == null || tenantId.equals(new UUID(0, 0))) { throw new CaptureFailure(INVALID_INPUT); }
        for (var id : new String[] { appId, accountId, installationId, repositoryId, workflowId }) { external(id); }
    }
    public WorkflowObservation.CaptureContext context() { return new WorkflowObservation.CaptureContext(tenantId, accountId, installationId, scopeRevision); }
    public WorkflowObservation.Pipeline pipeline() { return new WorkflowObservation.Pipeline(repositoryId, workflowId); }
    public static String safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) { throw new CaptureFailure(INVALID_INPUT); }
        return value;
    }
    public static String external(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) { throw new CaptureFailure(INVALID_INPUT); }
        return value;
    }
    @Override public String toString() { return "CaptureScope[redacted]"; }
}
