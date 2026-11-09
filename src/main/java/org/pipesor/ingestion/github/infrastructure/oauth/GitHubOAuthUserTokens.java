package org.pipesor.ingestion.github.infrastructure.oauth;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Failure.Reason.*;

/** One explicit POST, no redirects/retry loop. Does not establish identity, admission or browser continuity. */
public final class GitHubOAuthUserTokens implements GitHubUserTokens {
    private static final URI ENDPOINT = URI.create("https://github.com/login/oauth/access_token");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client;
    private final URI endpoint;
    private final URI redirect;
    private final Supplier<ClientCredentials> credentials;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final Clock clock;

    public record ClientCredentials(String clientId, String clientSecret) {
        public ClientCredentials { GitHubUserTokens.token(clientId); GitHubUserTokens.token(clientSecret); }
        @Override public String toString() { return "ClientCredentials[private]"; }
    }

    public GitHubOAuthUserTokens(Supplier<ClientCredentials> credentials, URI approvedRedirect,
            Duration timeout, int maxResponseBytes, Clock clock) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build(),
                ENDPOINT, credentials, approvedRedirect, timeout, maxResponseBytes, clock);
    }

    // Local HTTP fixtures use the same transport; runtime construction fixes the GitHub endpoint above.
    GitHubOAuthUserTokens(HttpClient client, URI endpoint, Supplier<ClientCredentials> credentials, URI approvedRedirect,
            Duration timeout, int maxResponseBytes, Clock clock) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.credentials = Objects.requireNonNull(credentials);
        this.redirect = Objects.requireNonNull(approvedRedirect);
        this.timeout = Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
        if (client.followRedirects() != HttpClient.Redirect.NEVER || !"https".equals(redirect.getScheme())
                || redirect.getHost() == null || redirect.getUserInfo() != null || redirect.getFragment() != null
                || redirect.getQuery() != null || redirect.getHost().endsWith(".invalid")
                || timeout.isNegative() || timeout.isZero() || maxResponseBytes < 1) {
            throw new IllegalArgumentException("Explicit secure OAuth transport configuration required");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override public Pair exchange(AuthorizationCode request) {
        if (request == null) { throw new Failure(INVALID_INPUT); }
        return post(Map.of("code", request.code(), "code_verifier", request.verifier(), "redirect_uri", redirect.toString()));
    }

    @Override public Pair refresh(String refreshToken) {
        try { GitHubUserTokens.token(refreshToken); }
        catch (RuntimeException invalid) { throw new Failure(INVALID_INPUT); }
        return post(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
    }

    private Pair post(Map<String, String> fields) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new Failure(INVALID_INPUT); }
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var app = Objects.requireNonNull(credentials.get());
            var form = new TreeMap<>(fields);
            form.put("client_id", app.clientId()); form.put("client_secret", app.clientSecret());
            var encoded = new StringJoiner("&");
            form.forEach((key, value) -> encoded.add(encode(key) + "=" + encode(value)));
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encoded.toString(), StandardCharsets.UTF_8)).build();
            // Durations begin before send; receiving a slow response must not extend credential validity.
            var started = clock.instant();
            pending = client.sendAsync(request, info -> new BoundedBody(maxResponseBytes));
            var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].strip().equalsIgnoreCase("application/json")) { throw new Failure(UNCERTAIN); }
            var body = JSON.readTree(response.body());
            if (!body.isObject()) { throw new Failure(UNCERTAIN); }
            if (body.has("error")) {
                var error = body.get("error");
                if (error.isString() && Set.of("bad_verification_code", "bad_refresh_token", "expired_token", "access_denied").contains(error.asString())) {
                    throw new Failure(REJECTED);
                }
                throw new Failure(UNCERTAIN);
            }
            if (!"bearer".equalsIgnoreCase(text(body, "token_type"))) { throw new Failure(UNCERTAIN); }
            var pair = new Pair(text(body, "access_token"), text(body, "refresh_token"),
                    started.plusSeconds(seconds(body, "expires_in")), started.plusSeconds(seconds(body, "refresh_token_expires_in")));
            if (!pair.accessExpiresAt().isAfter(clock.instant()) || !pair.refreshExpiresAt().isAfter(clock.instant())) { throw new Failure(UNCERTAIN); }
            return pair;
        } catch (Failure failure) { throw failure; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Failure(UNCERTAIN); }
        catch (Exception privateFailure) { throw new Failure(UNCERTAIN); }
        finally { if (pending != null && !pending.isDone()) { pending.cancel(true); } }
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static String text(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || !value.isString() || value.asString().isBlank()) { throw new Failure(UNCERTAIN); }
        return value.asString();
    }
    private static long seconds(JsonNode body, String name) {
        var value = body.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) { throw new Failure(UNCERTAIN); }
        return value.longValue();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new Failure(UNCERTAIN)); return;
                }
                while (buffer.hasRemaining()) { bytes.write(buffer.get()); }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { body.completeExceptionally(new Failure(UNCERTAIN)); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
