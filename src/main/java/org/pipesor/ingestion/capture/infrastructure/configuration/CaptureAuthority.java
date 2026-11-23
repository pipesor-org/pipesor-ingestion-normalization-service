package org.pipesor.ingestion.capture.infrastructure.configuration;

import java.util.function.Function;
import org.pipesor.ingestion.capture.application.ports.CurrentMachineScope;
import org.pipesor.ingestion.capture.application.ports.MachineScopes;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.Authorization;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.HookContext;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.Source;
import org.pipesor.ingestion.github.application.ports.GitHubRepositorySelection;
import org.pipesor.ingestion.github.application.ports.InstallationAdmission;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;

/** Runtime capture and admission authority (ADR-023); every check reads current state, no positive cache. */
public interface CaptureAuthority extends MachineScopes, CurrentMachineScope, InstallationAdmission,
        GitHubRepositorySelection {
    /** Readiness: the authority store answers; it does not authorize a request. */
    void validate();

    /** The caller supplies the trusted capture route, independently of the event body. */
    Decision verifyProtectedEvidenceScope(HookContext trustedRoute, WorkflowObservation.CaptureContext capture,
            WorkflowObservation.Pipeline pipeline);

    Function<Source, Authorization> webhook(HookContext hook);
}
