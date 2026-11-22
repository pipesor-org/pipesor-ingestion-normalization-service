package org.pipesor.ingestion.auth.application.ports;

import java.net.URI;

/** GitHub's authorize URL for the App's client id and the configured callback. */
public interface GitHubAuthorization {
    URI authorizeUrl(String state, String codeChallenge);
}
