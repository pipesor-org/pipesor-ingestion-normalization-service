package org.pipesor.ingestion.auth.application.services;

import java.net.URI;
import java.time.Instant;
import org.pipesor.ingestion.auth.application.commands.LoginCommands.*;

/** The GitHub App user-authorization login of the platform identity contract v1, §5. */
public interface GitHubLogin {
    /** Returns GitHub's authorize URL for a new one-time attempt. */
    URI start(Start command);
    /** Returns the one-time handoff value after the user, installation and repository checks pass. */
    String callback(Callback command);
    Session exchange(Exchange command);
    void logout(Logout command);

    record Session(String accessToken, Instant expiresAt, String githubUserId, String login, String returnPath) {
        @Override public String toString() { return "Session[redacted]"; }
    }
}
