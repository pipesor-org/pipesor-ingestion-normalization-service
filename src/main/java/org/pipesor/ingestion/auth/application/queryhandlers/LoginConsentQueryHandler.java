package org.pipesor.ingestion.auth.application.queryhandlers;

import java.util.Objects;
import java.util.Optional;
import org.pipesor.ingestion.auth.application.ports.ConsentRecords;
import org.pipesor.ingestion.auth.application.queries.CurrentConsentQuery;
import org.pipesor.ingestion.auth.application.queries.LoginConsent;
import org.pipesor.ingestion.auth.application.services.LoginConsentQueries;
import org.pipesor.ingestion.auth.application.services.LoginFailure;

/** A revoked most recent record hides older ones: only a new sign-in records consent again. */
public final class LoginConsentQueryHandler implements LoginConsentQueries {
    private final ConsentRecords records;

    public LoginConsentQueryHandler(ConsentRecords records) { this.records = Objects.requireNonNull(records); }

    @Override public Optional<LoginConsent> current(CurrentConsentQuery query) {
        Objects.requireNonNull(query);
        Optional<ConsentRecords.Recorded> latest;
        try { latest = records.latest(query.identity()); }
        catch (RuntimeException unavailable) { throw new LoginFailure(LoginFailure.Reason.UNAVAILABLE); }
        return latest.filter(recorded -> !recorded.revoked())
                .map(recorded -> new LoginConsent(recorded.documentsVersion(), recorded.acceptedAt(), recorded.source()));
    }
}
