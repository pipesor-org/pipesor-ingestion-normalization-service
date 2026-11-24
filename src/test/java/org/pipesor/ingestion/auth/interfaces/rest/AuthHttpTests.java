package org.pipesor.ingestion.auth.interfaces.rest;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.pipesor.ingestion.auth.application.commandhandlers.GitHubLoginHandler;
import org.pipesor.ingestion.auth.application.ports.GitHubLoginDirectory;
import org.pipesor.ingestion.auth.application.ports.SessionIssuer;
import org.pipesor.ingestion.auth.application.queryhandlers.LoginConsentQueryHandler;
import org.pipesor.ingestion.auth.application.services.GitHubLogin;
import org.pipesor.ingestion.auth.application.services.LoginConsentQueries;
import org.pipesor.ingestion.auth.infrastructure.persistence.JdbcConsentRecords;
import org.pipesor.ingestion.auth.infrastructure.persistence.JdbcLoginAttempts;
import org.pipesor.ingestion.auth.infrastructure.persistence.SpringLoginTransactions;
import org.pipesor.ingestion.configuration.OpenApiConfiguration;
import org.pipesor.ingestion.configuration.SessionProperties;
import org.pipesor.ingestion.configuration.TestConnectionConfiguration;
import org.pipesor.ingestion.configuration.WebConfiguration;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryFacts;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.pipesor.ingestion.github.infrastructure.custody.FileCredentialCustody;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.ConnectionJpaEntities;
import org.pipesor.ingestion.shared.interfaces.rest.ApiExceptionHandler;
import org.pipesor.ingestion.shared.security.PlatformIds;
import org.pipesor.ingestion.shared.security.PlatformSecurityConfiguration;
import org.pipesor.ingestion.shared.security.PlatformTestIdentity;
import org.pipesor.ingestion.shared.security.SessionTokens;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The login routes over real HTTP with the platform filter chain, real PostgreSQL (attempts, connection
 * model, consent records) and real session signing; only GitHub is synthetic.
 */
class AuthHttpTests {
    private static final UUID TENANT = PlatformTestIdentity.TENANT;
    private static final String DASHBOARD = "http://127.0.0.1:5173";
    private static final String VERSION = "2026-11-24";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SyntheticGitHub GITHUB = new SyntheticGitHub();
    private static final List<URI> AUTHORIZE_URLS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static PostgreSQLContainer postgres;
    private static PlatformTestIdentity identity;
    private static Path directory;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static URI base;
    private static HttpClient client;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
    @EntityScan(basePackageClasses = ConnectionJpaEntities.class)
    @EnableConfigurationProperties(SessionProperties.class)
    @Import({TestConnectionConfiguration.class, PlatformSecurityConfiguration.class, WebConfiguration.class, OpenApiConfiguration.class,
            AuthController.class, ApiExceptionHandler.class})
    static class HttpApplication {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean GitHubLogin githubLogin(DataSource dataSource, PlatformTransactionManager transactions, GitHubConnections connections,
                ConnectionStatusReader statuses, Clock clock) {
            var tokens = new SessionTokens(TENANT, PlatformTestIdentity.key(identity.session), Duration.ofHours(1), clock);
            SessionIssuer issuer = id -> { var token = tokens.issue(id); return new SessionIssuer.Issued(token.value(), token.expiresAt()); };
            var jdbc = new JdbcTemplate(dataSource);
            return new GitHubLoginHandler(TENANT, new JdbcLoginAttempts(jdbc), (state, challenge) -> {
                var uri = URI.create("https://github.com/login/oauth/authorize?state=" + state + "&code_challenge=" + challenge);
                AUTHORIZE_URLS.add(uri);
                return uri;
            }, GITHUB, GITHUB, new FileCredentialCustody(directory.resolve("custody"), 24000), connections, statuses, issuer,
                    new JdbcConsentRecords(jdbc), new SpringLoginTransactions(transactions), clock);
        }
        @Bean LoginConsentQueries loginConsentQueries(DataSource dataSource) {
            return new LoginConsentQueryHandler(new JdbcConsentRecords(new JdbcTemplate(dataSource)));
        }
    }

    static final class SyntheticGitHub implements GitHubUserTokens, GitHubLoginDirectory {
        final AtomicInteger exchanges = new AtomicInteger();
        @Override public Pair exchange(AuthorizationCode request) {
            exchanges.incrementAndGet();
            var now = Instant.now();
            return new Pair("ghu_access" + exchanges.get(), "ghr_refresh" + exchanges.get(), now.plus(Duration.ofHours(8)), now.plus(Duration.ofDays(180)));
        }
        @Override public Pair refresh(String refreshToken) { throw new AssertionError("no refresh in login tests"); }
        @Override public Facts read(String userAccessToken, Instant accessExpiresAt) {
            return new Facts("42", "octocat", List.of(new RepositoryFacts(new RepositoryTarget("480", "360"), "240",
                    true, true, true, true, true, "org/repo", "main")));
        }
    }

    @BeforeAll static void start(@TempDir Path temporary) {
        directory = temporary;
        identity = new PlatformTestIdentity();
        postgres = new PostgreSQLContainer("postgres:17.11");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("ingestion").schemas("ingestion").load().migrate();
        var properties = new ArrayList<>(identity.properties(directory));
        properties.addAll(List.of("server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off", "logging.level.root=WARN",
                "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=false",
                "spring.datasource.url=" + postgres.getJdbcUrl(), "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword(),
                "pipesor.session.signing-key-file=" + directory.resolve("unused-session.pem"),
                "pipesor.session.dashboard-origin=" + DASHBOARD,
                "pipesor.api.limits.request-timeout=30s", "pipesor.api.limits.max-batch-size=5",
                "pipesor.api.limits.max-page-size=100", "pipesor.api.limits.max-request-bytes=16384",
                "pipesor.api.limits.max-response-bytes=1000000", "pipesor.api.limits.max-context-length=128"));
        context = new SpringApplicationBuilder(HttpApplication.class).profiles("local")
                .initializers(c -> org.springframework.boot.test.util.TestPropertyValues.of(properties.toArray(String[]::new)).applyTo(c)).run();
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        base = URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort());
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterAll static void stop() {
        if (client != null) client.close();
        if (context != null) context.close();
        if (postgres != null) postgres.stop();
        if (identity != null) identity.close();
    }

    @BeforeEach void reset() { AUTHORIZE_URLS.clear(); GITHUB.exchanges.set(0); }

    @Test void startWithoutOneValidConsentVersionRedirectsWithoutAnAttemptOrGitHubCall() throws Exception {
        int attempts = attempts();
        var future = LocalDate.now(ZoneOffset.UTC).plusDays(2).toString();
        for (var query : List.of("", "&consent_version=", "&consent_version=2026-11-31", "&consent_version=2026-1-24",
                "&consent_version=24%2F11%2F2026", "&consent_version=%2B2026-11-24", "&consent_version=" + future,
                "&consent_version=" + VERSION + "&consent_version=" + VERSION, "&consent_version=" + VERSION + "&consent_version=")) {
            var response = get("/api/v1/auth/github/start?return_to=/pipelines" + query, null);
            assertEquals(302, response.statusCode(), query);
            assertEquals(DASHBOARD + "/auth/complete?error=consent_required", response.headers().firstValue("Location").orElseThrow(), query);
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
        assertEquals(attempts, attempts());
        assertTrue(AUTHORIZE_URLS.isEmpty());
        assertEquals(0, GITHUB.exchanges.get());
        // An invalid return_to keeps its 400 once consent is present.
        assertEquals(400, get("/api/v1/auth/github/start?return_to=https://evil.example&consent_version=" + VERSION, null).statusCode());
        assertEquals(attempts, attempts());
    }

    @Test void aSuccessfulLoginRecordsConsentAndTheConsentRouteReportsAndHidesRevokedConsent() throws Exception {
        var start = get("/api/v1/auth/github/start?return_to=/pipelines&consent_version=" + VERSION, null);
        assertEquals(302, start.statusCode(), start.body());
        var state = query(URI.create(start.headers().firstValue("Location").orElseThrow()), "state");
        var attempt = jdbc.queryForMap("select id, created_at, consent_version from ingestion.github_login_attempts order by created_at desc limit 1");
        assertEquals(java.sql.Date.valueOf(VERSION), attempt.get("consent_version"));

        var callback = get("/api/v1/auth/github/callback?code=synthetic-code&state=" + state, null);
        assertEquals(302, callback.statusCode());
        var location = URI.create(callback.headers().firstValue("Location").orElseThrow());
        assertTrue(location.toString().startsWith(DASHBOARD + "/auth/complete?handoff="), location.toString());
        var rows = jdbc.queryForList("select * from ingestion.consent_records where login_attempt_id = ?", attempt.get("id"));
        assertEquals(1, rows.size());
        assertEquals(PlatformIds.human(42), rows.getFirst().get("entra_object_id"));
        assertEquals("LOGIN_CHECKBOX", rows.getFirst().get("source"));
        var acceptedAt = ((java.sql.Timestamp) attempt.get("created_at")).toInstant();
        assertEquals(acceptedAt, ((java.sql.Timestamp) rows.getFirst().get("accepted_at")).toInstant());

        var exchange = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/session")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"handoff\":\"" + query(location, "handoff") + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, exchange.statusCode(), exchange.body());
        var session = JSON.readTree(exchange.body());
        assertEquals(Set.of("accessToken", "expiresAt", "user", "returnTo", "consent"), fields(session));
        assertEquals("42", session.get("user").get("githubUserId").asString());
        assertEquals("/pipelines", session.get("returnTo").asString());
        assertEquals(Set.of("documentsVersion", "acceptedAt"), fields(session.get("consent")));
        assertEquals(VERSION, session.get("consent").get("documentsVersion").asString());
        assertEquals(acceptedAt, Instant.parse(session.get("consent").get("acceptedAt").asString()));
        var token = session.get("accessToken").asString();

        var consent = get("/api/v1/auth/consent", token);
        assertEquals(200, consent.statusCode(), consent.body());
        assertEquals("no-store", consent.headers().firstValue("Cache-Control").orElseThrow());
        var body = JSON.readTree(consent.body());
        assertEquals(Set.of("documentsVersion", "acceptedAt", "source"), fields(body));
        assertEquals(VERSION, body.get("documentsVersion").asString());
        assertEquals(acceptedAt, Instant.parse(body.get("acceptedAt").asString()));
        assertEquals("LOGIN_CHECKBOX", body.get("source").asString());

        // The documented manual revocation procedure.
        assertEquals(1, jdbc.update("""
                update ingestion.consent_records set revoked_at = now(), revocation_reference = 'REQ-2026-0001'
                where tenant_id = ? and entra_object_id = ? and revoked_at is null""", TENANT, PlatformIds.human(42)));
        var revoked = get("/api/v1/auth/consent", token);
        assertEquals(404, revoked.statusCode(), revoked.body());
        assertTrue(revoked.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
        assertEquals("no-store", revoked.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("CONSENT_NOT_FOUND", JSON.readTree(revoked.body()).get("code").asString());
    }

    @Test void theConsentRouteRequiresASelfSession() throws Exception {
        var anonymous = get("/api/v1/auth/consent", null);
        assertEquals(401, anonymous.statusCode());
        assertEquals("UNAUTHENTICATED", JSON.readTree(anonymous.body()).get("code").asString());
        assertEquals(403, get("/api/v1/auth/consent", identity.workload("Pipeline.GitHubFacts.Read")).statusCode());
        assertEquals(403, get("/api/v1/auth/consent", identity.session(7, Instant.now().plusSeconds(60),
                Map.of("scp", "Pipeline.Access"), identity.session)).statusCode());
        var none = get("/api/v1/auth/consent", identity.session(7));
        assertEquals(404, none.statusCode());
        assertEquals("CONSENT_NOT_FOUND", JSON.readTree(none.body()).get("code").asString());
    }

    @Test void openApiDocumentsTheConsentContract() throws Exception {
        var response = get("/v3/api-docs", null);
        assertEquals(200, response.statusCode(), response.body());
        var schema = JSON.readTree(response.body());
        java.nio.file.Files.writeString(Path.of("target/auth-openapi.json"), response.body());
        var start = schema.get("paths").get("/api/v1/auth/github/start").get("get");
        JsonNode version = null;
        for (var parameter : start.get("parameters")) { if (parameter.get("name").asString().equals("consent_version")) version = parameter; }
        assertNotNull(version, start.toString());
        assertEquals("query", version.get("in").asString());
        assertTrue(version.get("required").asBoolean());
        assertEquals("date", version.get("schema").get("format").asString());
        assertEquals("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", version.get("schema").get("pattern").asString());
        assertTrue(start.get("responses").get("302").get("description").asString().contains("consent_required"));
        assertTrue(schema.get("paths").get("/api/v1/auth/github/callback").get("get").get("description").asString().contains("consent_required"));

        var schemas = schema.get("components").get("schemas");
        var sessionConsent = schemas.get("SessionResponse").get("properties").get("consent");
        assertEquals("#/components/schemas/SessionConsent", sessionConsent.has("$ref") ? sessionConsent.get("$ref").asString()
                : sessionConsent.get("allOf").get(0).get("$ref").asString());
        assertEquals("date", schemas.get("SessionConsent").get("properties").get("documentsVersion").get("format").asString());
        assertEquals("date-time", schemas.get("SessionConsent").get("properties").get("acceptedAt").get("format").asString());

        var consent = schema.get("paths").get("/api/v1/auth/consent").get("get");
        assertEquals("#/components/schemas/LoginConsent",
                consent.get("responses").get("200").get("content").get("application/json").get("schema").get("$ref").asString());
        assertEquals("#/components/schemas/Problem",
                consent.get("responses").get("404").get("content").get("application/problem+json").get("schema").get("$ref").asString());
        assertTrue(consent.has("security"));
        var login = schemas.get("LoginConsent").get("properties");
        assertEquals("date", login.get("documentsVersion").get("format").asString());
        assertEquals("[\"LOGIN_CHECKBOX\"]", login.get("source").get("enum").toString());
        assertEquals(Set.of("documentsVersion", "acceptedAt", "source"), new TreeSet<>(JSON.convertValue(
                schemas.get("LoginConsent").get("required"), List.class)));
    }

    private static int attempts() { return jdbc.queryForObject("select count(*) from ingestion.github_login_attempts", Integer.class); }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> fields(JsonNode node) { return new TreeSet<String>(JSON.convertValue(node, Map.class).keySet()); }

    private static String query(URI uri, String name) {
        for (var pair : uri.getRawQuery().split("&")) {
            var parts = pair.split("=", 2);
            if (parts[0].equals(name)) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError(name);
    }
}
