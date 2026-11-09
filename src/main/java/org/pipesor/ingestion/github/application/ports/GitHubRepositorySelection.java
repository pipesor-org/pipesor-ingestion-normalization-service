package org.pipesor.ingestion.github.application.ports;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;

/** Current trusted user-bound resolution, never an installation ID selected by the caller.
 * Return exactly the requested IDs; empty means denied/absent without disclosure. Ambiguity/outage must fail unavailable.
 * Resolution does not authorize later reads; queries still verify current user, installation, admission and generation.
 */
public interface GitHubRepositorySelection {
    Observation<Request, Map<String, Optional<RepositoryTarget>>> resolve(Request request);
    record Request(AccessContext context, Set<String> repositoryIds) {
        public Request { java.util.Objects.requireNonNull(context); repositoryIds = Set.copyOf(repositoryIds); }
    }
}
