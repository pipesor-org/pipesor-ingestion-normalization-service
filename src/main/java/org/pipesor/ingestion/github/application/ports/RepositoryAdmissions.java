package org.pipesor.ingestion.github.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Repository administrator self-service admission (ADR-024). Every decision is taken by the caller from facts read
 * from GitHub with the subject's own token; nothing here trusts browser-supplied installation data.
 */
public interface RepositoryAdmissions {
    /**
     * Admits a repository not yet admitted, or reactivates a revoked admission; returns true only when this call granted
     * it. An active admission bound to another account or installation is left untouched and yields false.
     */
    boolean admitByAdministrator(Admission admission);

    /** Revokes this installation's active admissions whose repositories GitHub no longer lists; returns their IDs. */
    List<String> revokeAbsent(Revocation revocation);

    record Admission(String tenantId, String accountId, String installationId, String repositoryId, String subjectRef,
                     Instant at) {
        public Admission {
            Objects.requireNonNull(at);
            for (var value : new String[]{tenantId, accountId, installationId, repositoryId, subjectRef}) {
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Admission facts are required");
            }
        }
    }

    record Revocation(String tenantId, String installationId, Set<String> listedRepositoryIds, String subjectRef,
                      Instant at) {
        public Revocation {
            Objects.requireNonNull(at);
            listedRepositoryIds = Set.copyOf(listedRepositoryIds);
            if (tenantId == null || tenantId.isBlank() || installationId == null || installationId.isBlank() || subjectRef == null || subjectRef.isBlank()) {
                throw new IllegalArgumentException("Revocation facts are required");
            }
        }
    }
}
