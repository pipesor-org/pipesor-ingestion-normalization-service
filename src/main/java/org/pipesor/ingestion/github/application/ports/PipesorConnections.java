package org.pipesor.ingestion.github.application.ports;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;

/**
 * Which principals of one page currently hold a usable Pipesor GitHub link in the tenant, with the definition of
 * {@link CurrentGitHubLink}: the principal's connection points at an active generation of that same GitHub user whose
 * refresh credential has not expired. Nothing is refreshed and no credential is read. One batched read per call, never
 * one per principal, and never inside a GitHub read.
 */
public interface PipesorConnections {
    /** The subset of the requested principals that is currently linked. */
    Observation<Request, Set<Principal>> active(Request request);

    /** {@code objectId} is the platform object ID of {@code githubUserId} (platform identity contract v1). */
    record Principal(UUID objectId, String githubUserId) {
        public Principal {
            Objects.requireNonNull(objectId, "Object required");
            AccessContext.requireText(githubUserId);
        }
    }

    record Request(UUID tenantId, Set<Principal> principals) {
        public static final int MAX_PRINCIPALS = ReadOnlyGitHub.CollaboratorsRequest.MAX_PAGE_SIZE;

        public Request {
            Objects.requireNonNull(tenantId, "Tenant required");
            principals = Set.copyOf(principals);
            if (principals.size() > MAX_PRINCIPALS) { throw new IllegalArgumentException("Bounded principal batch required"); }
        }
    }
}
