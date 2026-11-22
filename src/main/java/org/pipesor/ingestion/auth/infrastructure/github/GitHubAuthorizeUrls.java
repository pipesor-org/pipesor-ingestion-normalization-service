package org.pipesor.ingestion.auth.infrastructure.github;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import org.pipesor.ingestion.auth.application.ports.GitHubAuthorization;

/** {@code <web base>/login/oauth/authorize} with the App client id, the fixed callback, state and PKCE S256. */
public final class GitHubAuthorizeUrls implements GitHubAuthorization {
    private final URI webBase;
    private final URI callback;
    private final Supplier<String> clientId;

    public GitHubAuthorizeUrls(URI webBase, URI callback, Supplier<String> clientId) {
        this.webBase = Objects.requireNonNull(webBase);
        this.callback = Objects.requireNonNull(callback);
        this.clientId = Objects.requireNonNull(clientId);
    }

    @Override public URI authorizeUrl(String state, String codeChallenge) {
        return webBase.resolve("/login/oauth/authorize?client_id=" + encode(clientId.get())
                + "&redirect_uri=" + encode(callback.toString()) + "&state=" + encode(state)
                + "&code_challenge=" + encode(codeChallenge) + "&code_challenge_method=S256");
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
