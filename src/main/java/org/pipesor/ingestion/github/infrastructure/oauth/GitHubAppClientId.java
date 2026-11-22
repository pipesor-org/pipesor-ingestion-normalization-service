package org.pipesor.ingestion.github.infrastructure.oauth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubMachineTransport.AppJwt;
import tools.jackson.databind.json.JsonMapper;

/**
 * The GitHub App's OAuth client id: the configured value, or {@code GET /app} with the App JWT
 * (verifying the App id), cached after the first successful read.
 */
public final class GitHubAppClientId implements Supplier<String> {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BYTES = 65536;
    private final HttpClient client;
    private final URI apiBase;
    private final Supplier<AppJwt> jwt;
    private final String appId;
    private final String apiVersion;
    private final Duration timeout;
    private volatile String clientId;

    public GitHubAppClientId(String configured, HttpClient client, URI apiBase, Supplier<AppJwt> jwt, String appId,
            String apiVersion, Duration timeout) {
        this.clientId = configured;
        this.client = Objects.requireNonNull(client); this.apiBase = Objects.requireNonNull(apiBase);
        this.jwt = Objects.requireNonNull(jwt); this.appId = Objects.requireNonNull(appId);
        this.apiVersion = Objects.requireNonNull(apiVersion); this.timeout = Objects.requireNonNull(timeout);
    }

    @Override public String get() {
        var known = clientId;
        if (known != null) return known;
        try {
            var request = HttpRequest.newBuilder(apiBase.resolve("/app")).timeout(timeout).GET()
                    .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", apiVersion)
                    .header("Authorization", "Bearer " + jwt.get().token()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (var input = response.body()) { body = input.readNBytes(MAX_BYTES + 1); }
            if (response.statusCode() != 200 || body.length > MAX_BYTES) throw new IllegalStateException();
            var app = JSON.readTree(body);
            var id = app.get("id");
            var value = app.get("client_id");
            if (id == null || !appId.equals(id.asText()) || value == null || !value.isString()
                    || !value.asString().matches("[A-Za-z0-9._-]{1,128}")) {
                throw new IllegalStateException();
            }
            clientId = value.asString();
            return clientId;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub App client id unavailable");
        } catch (Exception unavailable) {
            throw new IllegalStateException("GitHub App client id unavailable");
        }
    }

    @Override public String toString() { return "GitHubAppClientId[configured]"; }
}
