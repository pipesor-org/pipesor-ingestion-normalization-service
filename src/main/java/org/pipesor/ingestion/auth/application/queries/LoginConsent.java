package org.pipesor.ingestion.auth.application.queries;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.pipesor.ingestion.auth.application.ports.ConsentRecords.Source;

/** A recorded, non-revoked login consent: the accepted documents version and when it was accepted. */
public record LoginConsent(LocalDate documentsVersion, Instant acceptedAt, Source source) {
    public LoginConsent {
        Objects.requireNonNull(documentsVersion); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(source);
    }
}
