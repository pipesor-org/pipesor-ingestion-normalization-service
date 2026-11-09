package org.pipesor.ingestion.github.application.ports;

import java.time.Instant;
import java.util.Objects;

/** Private side-effect boundary. A trusted executor must durably claim before sending a code/refresh.
 * An uncertain response never authorizes another send, activation or fallback to old credentials. */
public interface GitHubUserTokens {
    Pair exchange(AuthorizationCode request);
    Pair refresh(String refreshToken);

    record AuthorizationCode(String code, String verifier) {
        public AuthorizationCode {
            token(code);
            if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) {
                throw new IllegalArgumentException("Invalid private authorization input");
            }
        }
        @Override public String toString() { return "AuthorizationCode[private]"; }
    }

    record Pair(String accessToken, String refreshToken, Instant accessExpiresAt, Instant refreshExpiresAt) {
        public Pair {
            token(accessToken); token(refreshToken);
            Objects.requireNonNull(accessExpiresAt, "Access expiry required");
            Objects.requireNonNull(refreshExpiresAt, "Refresh expiry required");
        }
        @Override public String toString() { return "Pair[private]"; }
    }

    static void token(String value) {
        if (value == null || value.isEmpty() || value.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
            throw new IllegalArgumentException("Invalid private credential input");
        }
    }

    final class Failure extends RuntimeException {
        public enum Reason { INVALID_INPUT, REJECTED, UNCERTAIN }
        private final Reason reason;
        public Failure(Reason reason) {
            super("GitHub token operation " + reason.name(), null, false, false);
            this.reason = reason;
        }
        public Reason reason() { return reason; }
    }
}
