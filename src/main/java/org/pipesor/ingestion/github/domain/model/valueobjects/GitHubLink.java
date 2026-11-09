package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.time.Instant;
import java.util.Objects;

/** Value snapshot of the link revision, not its lifecycle entity; credentials stay behind the ports. */
public record GitHubLink(String tenantId, String subjectRef, String githubUserId, String revision, Instant updatedAt) {
    public GitHubLink {
        AccessContext.requireText(tenantId);
        AccessContext.requireText(subjectRef);
        AccessContext.requireText(githubUserId);
        AccessContext.requireText(revision);
        Objects.requireNonNull(updatedAt, "Source update time required");
    }
}
