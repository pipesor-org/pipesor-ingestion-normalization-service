package org.pipesor.ingestion.github.application.ports;

import java.util.Optional;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.GitHubLink;

/** Present means a currently usable link; missing/revoked links require reconnection, not an inferred updatedAt expiry.
 * Every connection/reconnection changes the opaque link revision, even at the same source updatedAt.
 * This is a logical read requirement, not a physical persistence or credential-storage design. */
public interface CurrentGitHubLink {
    Observation<AccessContext, Optional<GitHubLink>> read(AccessContext context);
}
