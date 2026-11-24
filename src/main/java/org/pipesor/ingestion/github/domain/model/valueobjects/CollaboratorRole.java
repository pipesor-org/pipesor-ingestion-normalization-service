package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.util.Optional;

/**
 * A collaborator's effective repository role, derived only from GitHub's permission flags. GitHub's custom role
 * names ({@code role_name}) are never exposed.
 */
public enum CollaboratorRole {
    READ, TRIAGE, WRITE, MAINTAIN, ADMIN;

    /** The highest granted role, or empty when GitHub grants no permission at all. */
    public static Optional<CollaboratorRole> of(boolean admin, boolean maintain, boolean push, boolean triage, boolean pull) {
        if (admin) { return Optional.of(ADMIN); }
        if (maintain) { return Optional.of(MAINTAIN); }
        if (push) { return Optional.of(WRITE); }
        if (triage) { return Optional.of(TRIAGE); }
        return pull ? Optional.of(READ) : Optional.empty();
    }
}
