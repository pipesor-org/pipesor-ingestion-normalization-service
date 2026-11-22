package org.pipesor.ingestion.auth.application.ports;

import java.time.Instant;

/** Signs a human session token for a verified GitHub user (platform identity contract v1, §3). */
public interface SessionIssuer {
    Issued issue(long githubUserId);

    record Issued(String accessToken, Instant expiresAt) {
        @Override public String toString() { return "IssuedSession[redacted]"; }
    }
}
