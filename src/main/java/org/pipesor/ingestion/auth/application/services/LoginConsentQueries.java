package org.pipesor.ingestion.auth.application.services;

import java.util.Optional;
import org.pipesor.ingestion.auth.application.queries.CurrentConsentQuery;
import org.pipesor.ingestion.auth.application.queries.LoginConsent;

/** Reads the consent recorded at sign-in (ADR-029). */
public interface LoginConsentQueries {
    /** The principal's most recent consent, or empty when there is none or the most recent one is revoked. */
    Optional<LoginConsent> current(CurrentConsentQuery query);
}
