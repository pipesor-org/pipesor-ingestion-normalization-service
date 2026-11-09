package org.pipesor.ingestion.github.application.ports;

import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Pair;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;

/** Private custody only: a stored version is not evidence of an active generation or current authorization. */
public interface GitHubCredentialCustody {
    SavedVersion savePair(SecretReference generationObject, Pair pair);
    /** Preserves provider expiries; callers must enforce the appropriate expiry before using either token. */
    Pair readPair(SavedVersion version);
    SavedVersion saveVerifier(SecretReference attemptObject, String verifier);
    String readVerifier(SavedVersion version);

    final class Failure extends RuntimeException {
        public enum Reason { INVALID_INPUT, UNAVAILABLE }
        private final Reason reason;
        public Failure(Reason reason) {
            super("GitHub credential custody " + reason.name().toLowerCase(java.util.Locale.ROOT), null, false, false);
            this.reason = reason;
        }
        public Reason reason() { return reason; }
    }
}
