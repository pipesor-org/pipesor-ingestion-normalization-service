package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Value snapshot of the link revision, not its lifecycle entity; credentials stay behind the ports. */
public record GitHubLink(String tenantId, String subjectRef, String githubUserId, String revision, Instant updatedAt,
                         UUID generationId) {
    /** Legacy local queries have no durable generation binding and cannot produce HTTP verification facts. */
    public GitHubLink(String tenantId, String subjectRef, String githubUserId, String revision, Instant updatedAt) {
        this(tenantId, subjectRef, githubUserId, revision, updatedAt, null);
    }
    public GitHubLink {
        AccessContext.requireText(tenantId);
        AccessContext.requireText(subjectRef);
        AccessContext.requireText(githubUserId);
        AccessContext.requireText(revision);
        Objects.requireNonNull(updatedAt, "Source update time required");
    }
}
