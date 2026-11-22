package org.pipesor.ingestion.github.infrastructure.oauth;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.*;
import org.springframework.boot.test.system.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Failure.Reason.*;

@ExtendWith(OutputCaptureExtension.class)
class GitHubOAuthUserTokensTests {
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final String BODY = "{\"access_token\":\"ghu_private&+access\",\"refresh_token\":\"ghr_private&+refresh\",\"token_type\":\"bearer\",\"expires_in\":173,\"refresh_token_expires_in\":937}";
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private URI endpoint;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = BODY;
    private volatile String requestBody;
    private volatile String query;
    private volatile long delay;
    private volatile boolean drop;
    private volatile Instant now = NOW;
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/token", exchange -> {
            requests.incrementAndGet();
            query = exchange.getRequestURI().getRawQuery();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Accept"));
            if (drop) { exchange.close(); return; }
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 302) { exchange.getResponseHeaders().add("Location", endpoint.toString()); }
            exchange.sendResponseHeaders(status, bytes.length);
            now = NOW.plusSeconds(7);
            try {
                if (delay > 0) { Thread.sleep(delay); }
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException cancelled) { /* Timed-out test client has closed its response. */ }
            finally { exchange.close(); }
        });
        server.start(); endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
    }
    @AfterEach void stop() { server.stop(0); executor.close(); }
    private GitHubOAuthUserTokens adapter(int bytes, Duration timeout) {
        return new GitHubOAuthUserTokens(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), endpoint,
                () -> new GitHubOAuthUserTokens.ClientCredentials("app-client", "private&+client"),
                URI.create("https://ingestion.pipesor.example/callback"), timeout, bytes, clock, org.pipesor.ingestion.shared.security.TransportPolicy.forProfiles("local"));
    }
    private GitHubOAuthUserTokens adapter() { return adapter(4096, Duration.ofSeconds(3)); }

    @Test void exchangesCodeWithPkceWithoutQuerySecretsAndConservativelyPreservesProviderExpiries(CapturedOutput output) {
        var code = new AuthorizationCode("private&+code", "v".repeat(43));
        var pair = adapter().exchange(code);
        assertEquals(1, requests.get()); assertNull(query);
        var fields = fields();
        assertEquals("private&+code", fields.get("code"));
        assertEquals("v".repeat(43), fields.get("code_verifier"));
        assertEquals("private&+client", fields.get("client_secret"));
        assertEquals("https://ingestion.pipesor.example/callback", fields.get("redirect_uri"));
        assertFalse(fields.containsKey("grant_type"));
        assertEquals("ghu_private&+access", pair.accessToken());
        assertEquals("ghr_private&+refresh", pair.refreshToken());
        assertEquals(NOW.plusSeconds(173), pair.accessExpiresAt());
        assertEquals(NOW.plusSeconds(937), pair.refreshExpiresAt());
        assertFalse(pair.toString().contains("private&")); assertFalse(code.toString().contains("private&"));
        assertFalse(new GitHubOAuthUserTokens.ClientCredentials("app", "private-client").toString().contains("private-client"));
        assertFalse(output.getAll().contains("private&"));
    }

    @Test void refreshSendsOnlyRefreshGrantAndReturnsTheNewAtomicPair() {
        body = BODY.replace("ghu_private&+access", "new-access-42").replace("ghr_private&+refresh", "new-refresh-83")
                .replace("173", "289").replace("937", "1501");
        var pair = adapter().refresh("old-private&+refresh");
        assertEquals(1, requests.get());
        assertEquals("refresh_token", fields().get("grant_type"));
        assertEquals("old-private&+refresh", fields().get("refresh_token"));
        assertFalse(fields().containsKey("code")); assertFalse(fields().containsKey("redirect_uri"));
        assertEquals("new-access-42", pair.accessToken()); assertEquals("new-refresh-83", pair.refreshToken());
        assertEquals(NOW.plusSeconds(289), pair.accessExpiresAt());
        assertEquals(NOW.plusSeconds(1501), pair.refreshExpiresAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "not-json private-body", "{\"error\":\"private-provider-body\"}",
            "duplicate",
            "missing-refresh", "missing-access-expiry", "fractional", "zero", "overflow", "wrong-type", "non-bearer", "trailing-object", "trailing-garbage"})
    void malformedIncompatibleOrUnknownResultsAreUncertainAndNeverRetried(String scenario, CapturedOutput output) {
        body = switch (scenario) {
            case "duplicate" -> BODY.replace("\"access_token\":", "\"access_token\":\"private-first\",\"access_token\":");
            case "missing-refresh" -> BODY.replace("\"refresh_token\":\"ghr_private&+refresh\",", "");
            case "missing-access-expiry" -> BODY.replace("\"expires_in\":173,", "");
            case "fractional" -> BODY.replace("173", "173.4");
            case "zero" -> BODY.replace("173", "0");
            case "overflow" -> BODY.replace("173", "999999999999999999999999999");
            case "wrong-type" -> BODY.replace("173", "\"173\"");
            case "trailing-object" -> BODY + " {}";
            case "trailing-garbage" -> BODY + " private-garbage";
            case "non-bearer" -> BODY.replace("bearer", "basic");
            default -> scenario;
        };
        failure(UNCERTAIN, () -> adapter().refresh("private-old-refresh"));
        assertEquals(1, requests.get()); assertFalse(output.getAll().contains("private-"));
    }

    @Test void rejectsDefinitiveProviderFailureWithoutEchoingProviderTextOrRetrying() {
        body = "{\"error\":\"bad_refresh_token\",\"error_description\":\"private-provider-token\"}";
        failure(REJECTED, () -> adapter().refresh("private-refresh"));
        assertEquals(1, requests.get());
    }

    @Test void redirectAndProviderOutageNeverTriggerAnotherTokenRequest() {
        status = 302; failure(UNCERTAIN, () -> adapter().refresh("private-refresh"));
        assertEquals(1, requests.get());
        status = 503; failure(UNCERTAIN, () -> adapter().refresh("private-refresh"));
        assertEquals(2, requests.get());
    }

    @Test void boundsResponseBytesAndTotalBodyTimeWithoutRetry() {
        body = "private".repeat(10000);
        failure(UNCERTAIN, () -> adapter(128, Duration.ofSeconds(2)).refresh("private-refresh"));
        assertEquals(1, requests.get());
        body = BODY; delay = 1200;
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> failure(UNCERTAIN,
                () -> adapter(4096, Duration.ofMillis(300)).refresh("private-refresh")));
        assertEquals(2, requests.get());
    }

    @Test void lostResponseNeverRetriesTheConsumedRefreshToken() {
        drop = true;
        failure(UNCERTAIN, () -> adapter().refresh("private-old-refresh"));
        assertEquals(1, requests.get());
    }

    @Test void invalidInputOrSqlTransactionNeverSends() {
        failure(INVALID_INPUT, () -> adapter().refresh("private bad token"));
        failure(INVALID_INPUT, () -> adapter().exchange(null));
        assertThrows(IllegalArgumentException.class, () -> new AuthorizationCode("private-code", "short"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { failure(INVALID_INPUT, () -> adapter().refresh("private-refresh")); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertEquals(0, requests.get());
    }

    @Test void transportPolicyAllowsLoopbackHttpOnlyUnderLocal() {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        java.util.function.Supplier<GitHubOAuthUserTokens.ClientCredentials> app = () -> new GitHubOAuthUserTokens.ClientCredentials("a", "b");
        var local = org.pipesor.ingestion.shared.security.TransportPolicy.forProfiles("local");
        var azure = org.pipesor.ingestion.shared.security.TransportPolicy.forProfiles("azure");
        var loopback = URI.create("http://127.0.0.1:8081/api/v1/auth/github/callback");
        assertNotNull(new GitHubOAuthUserTokens(client, endpoint, app, loopback, Duration.ofSeconds(1), 10, clock, local));
        assertThrows(IllegalArgumentException.class, () -> new GitHubOAuthUserTokens(client, URI.create("https://github.com/login/oauth/access_token"), app, loopback, Duration.ofSeconds(1), 10, clock, azure));
        assertThrows(IllegalArgumentException.class, () -> new GitHubOAuthUserTokens(client, endpoint, app, URI.create("https://ingestion.example/cb"), Duration.ofSeconds(1), 10, clock, azure));
        assertThrows(IllegalArgumentException.class, () -> new GitHubOAuthUserTokens(client, endpoint, app, URI.create("http://10.0.0.1/cb"), Duration.ofSeconds(1), 10, clock, local));
    }

    private Map<String, String> fields() {
        var fields = new HashMap<String, String>();
        for (var field : requestBody.split("&")) {
            var parts = field.split("=", 2);
            fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return fields;
    }
    private void failure(Failure.Reason reason, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(Failure.class, action);
        assertEquals(reason, failure.reason()); assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private"));
    }
}
