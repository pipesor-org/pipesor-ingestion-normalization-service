package org.pipesor.ingestion.github.interfaces.rest.resources;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;

public final class GitHubResources {
    private GitHubResources() {}
    private static final String NEXT_PAGE_TOKEN = "Opaque token of the next page for the same route, repository and q; "
            + "null on the last page and always null in the legacy limit form";
    public record AccessRequest(@Schema(example = "request-1", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
                                @Schema(example = "corr-http", requiredMode = Schema.RequiredMode.REQUIRED) String correlationId,
                                @Schema(example = "2026-11-09T12:00:30Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant deadline,
                                @Schema(example = "VIEW", requiredMode = Schema.RequiredMode.REQUIRED) AccessOperation operation,
                                @Schema(description = "Same-tenant recipient object ID; only GRANT operations", example = "33333333-3333-4333-8333-333333333333") UUID recipientObjectId,
                                @Schema(example = "[\"823001\"]", requiredMode = Schema.RequiredMode.REQUIRED) List<String> repositoryIds) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }
    public record WorkflowRequest(@Schema(example = "request-1", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
                                  @Schema(example = "corr-http", requiredMode = Schema.RequiredMode.REQUIRED) String correlationId,
                                  @Schema(example = "2026-11-09T12:00:30Z", requiredMode = Schema.RequiredMode.REQUIRED) Instant deadline,
                                  @Schema(allowableValues = "REGISTER", requiredMode = Schema.RequiredMode.REQUIRED) AccessOperation operation,
                                  @Schema(example = "823001", requiredMode = Schema.RequiredMode.REQUIRED) String repositoryId,
                                  @Schema(example = "12345", requiredMode = Schema.RequiredMode.REQUIRED) String workflowId) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field"); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RepositoryFact(String repositoryId, String installationId, String accountId, boolean admittedForTenant,
                                 boolean readable, boolean administrator) {}
    public record AccessVerification(String requestId, String correlationId, UUID tenantId, UUID actorObjectId,
                                     UUID subjectObjectId, AccessOperation operation, String githubUserId,
                                     UUID generationId, String revision, Instant observedAt, Instant validUntil,
                                     List<RepositoryFact> repositories) {}
    public record WorkflowVerification(AccessVerification access, String repositoryId, String workflowId,
                                       String fullName, String defaultBranch, String workflowName, String workflowFile) {}
    public record Repository(String repositoryId, String installationId, String fullName, String defaultBranch) {}
    public record Workflow(String workflowId, String name, String file) {}
    public record DiscoveryContext(String requestId, String correlationId, UUID tenantId, UUID actorObjectId, UUID subjectObjectId,
                                   AccessOperation operation, String githubUserId, UUID generationId, String revision,
                                   Instant observedAt, Instant validUntil) {}
    public record Repositories(DiscoveryContext context, List<Repository> repositories,
                               @Schema(description = NEXT_PAGE_TOKEN, types = {"string", "null"}, maxLength = 512,
                                       requiredMode = Schema.RequiredMode.REQUIRED) String nextPageToken) {}
    /** ADR-024: {@code admitted} means Pipesor may monitor the repository; {@code userIsAdmin} is the user's GitHub role. */
    public record RepositoryAdmission(String repositoryId, String fullName, boolean admitted, boolean userIsAdmin) {}
    public record Account(String id, String login) {}
    /** {@code suspended} installations list no repositories until GitHub resumes them. */
    public record Installation(String installationId, Account account, boolean suspended, List<RepositoryAdmission> repositories) {}
    public record Installations(DiscoveryContext context, List<Installation> installations) {}
    public record Workflows(DiscoveryContext context, Repository repository, List<Workflow> workflows,
                            @Schema(description = NEXT_PAGE_TOKEN, types = {"string", "null"}, maxLength = 512,
                                    requiredMode = Schema.RequiredMode.REQUIRED) String nextPageToken) {}
    public record Run(String runId, int attempt, String conclusion, String headBranch, String headSha, Instant createdAt,
                      Instant runStartedAt, String htmlUrl) {}
    public record Runs(List<Run> items, String nextPageToken) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Analysis(UUID analysisId, String phase, String blockedReason, UUID executionId, UUID incidentId) {}
    public record Connection(boolean locallyLinked, Long revision, String cleanupStatus) {}
}
