package org.pipesor.ingestion.github.interfaces.rest;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.configuration.*;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.queries.*;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubQueryHandler;
import org.pipesor.ingestion.github.application.services.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.shared.interfaces.rest.ApiExceptionHandler;
import org.pipesor.ingestion.shared.security.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.*;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real HTTP, the platform filter chain and decoder with loopback JWKS (Pipeline workload key and session
 * key); only the GitHub and persistence boundaries are synthetic.
 */
@ExtendWith(OutputCaptureExtension.class)
class GitHubHttpTests {
    private static final UUID TENANT = PlatformTestIdentity.TENANT;
    private static final long GITHUB_USER = 42;
    private static final UUID ACTOR = PlatformIds.human(GITHUB_USER);
    private static final UUID RECIPIENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID GENERATION = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID CONNECTION = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String SELF_PATH = "/api/v1/github/repositories?limit=5";
    private static final String ACCESS_PATH = "/internal/v1/github/access-verifications";
    private static final String FACTS = "Pipeline.GitHubFacts.Read";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SyntheticProvider PROVIDER = new SyntheticProvider();
    private static final MutableClock CLOCK = new MutableClock();
    private static PlatformTestIdentity identity;
    private static Path keys;
    private static ConfigurableApplicationContext context;
    private static URI base;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
    @Import({PlatformSecurityConfiguration.class, WebConfiguration.class, OpenApiConfiguration.class, GitHubController.class,
            ApiExceptionHandler.class, JwksController.class})
    static class HttpApplication {
        @Bean @Primary Clock testClock() { return CLOCK; }
        @Bean GitHubQueries queries() { return new GitHubQueryHandler(PROVIDER::link, request -> observed(request, true), PROVIDER, CLOCK); }
        @Bean GitHubRepositorySelection selections() { return PROVIDER::resolve; }
        @Bean GitHubConnectionQueries connectionQueries() {
            return query -> { PROVIDER.statusIdentity = query.identity(); return Optional.of(new ConnectionStatus(CONNECTION, 7, GENERATION, Instant.now(), Instant.now())); };
        }
        @Bean GitHubConnections connectionCommands() { return mock(GitHubConnections.class); }
    }

    @BeforeAll static void start(@TempDir Path directory) {
        identity = new PlatformTestIdentity();
        keys = directory;
        context = application().run();
        base = base(context);
    }
    @AfterAll static void stop() { if (context != null) { context.close(); } identity.close(); }
    @BeforeEach void reset() {
        PROVIDER.failure = null; PROVIDER.denied = false; PROVIDER.badSelection = false;
        PROVIDER.missingGeneration = false; PROVIDER.extraRepositories = false; PROVIDER.reads = 0;
        PROVIDER.changeGeneration = false; PROVIDER.linkReads = 0;
        PROVIDER.delay = false; CLOCK.offset = Duration.ZERO;
        org.mockito.Mockito.reset(context.getBean(GitHubConnections.class));
    }

    @Test void bindsWorkloadCallerSubjectRecipientGenerationAccountAndRequestValidity() throws Exception {
        var expiry = Instant.now().plusSeconds(10);
        var body = access("GRANT_CONFIGURE", RECIPIENT, List.of("823001", "823002"), Instant.now().plusSeconds(100));
        PROVIDER.denied = true;
        var response = request(base, ACCESS_PATH, identity.workload(FACTS, expiry, Map.of(), identity.pipeline), body,
                Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER)));
        assertEquals(200, response.statusCode(), response.body());
        var result = JSON.readTree(response.body());
        assertEquals("request-1", result.get("requestId").asString());
        assertEquals("corr-http", result.get("correlationId").asString());
        assertEquals(ACTOR.toString(), result.get("actorObjectId").asString());
        assertEquals(RECIPIENT.toString(), result.get("subjectObjectId").asString());
        assertEquals(GENERATION.toString(), result.get("generationId").asString());
        assertEquals("revision-4", result.get("revision").asString());
        assertEquals(expiry.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), Instant.parse(result.get("validUntil").asString()));
        assertEquals("account-7", result.get("repositories").get(0).get("accountId").asString());
        assertFalse(result.get("repositories").get(1).has("installationId"));
        assertFalse(result.get("repositories").get(1).has("accountId"));
        assertFalse(result.get("repositories").get(1).get("readable").asBoolean());
        assertEquals(TENANT + ":" + RECIPIENT, PROVIDER.lastContext.subjectRef());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aud", "iss", "tid", "oid", "scp", "role", "ver", "idtyp", "expired", "future", "signature", "lifetime"})
    void rejectsInvalidWorkloadTokensBeforeProviderReads(String invalid) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        switch (invalid) {
            case "aud" -> claims.put("aud", "urn:pipesor:api:pipeline");
            case "iss" -> claims.put("iss", "urn:pipesor:workload:rag");
            case "tid" -> claims.put("tid", UUID.randomUUID().toString());
            case "oid" -> claims.put("oid", UUID.randomUUID().toString());
            case "scp" -> claims.put("scp", "GitHub.Self");
            case "role" -> claims.put("roles", List.of("Rag.Query"));
            case "ver" -> claims.put("ver", "1.0");
            case "idtyp" -> claims.put("idtyp", "user");
            case "future" -> claims.put("nbf", Date.from(Instant.now().plusSeconds(500)));
            default -> { }
        }
        var expiry = Instant.now().plusSeconds(switch (invalid) { case "expired" -> -120; case "lifetime" -> 3600; default -> 120; });
        String bearer = identity.workload(FACTS, expiry, claims, invalid.equals("signature") ? PlatformTestIdentity.rsa() : identity.pipeline);
        var response = request(base, ACCESS_PATH, bearer, access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)),
                Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER)));
        assertEquals(401, response.statusCode(), response.body());
        assertEquals(0, PROVIDER.reads);
        assertEquals("UNAUTHENTICATED", JSON.readTree(response.body()).get("code").asString());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
        assertFalse(response.body().contains("invalid_token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "workload", "audience", "scope-free", "signature", "expired", "github-user"})
    void internalRoutesRequireExactlyOneValidSubjectSession(String invalid) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        switch (invalid) {
            case "missing" -> { }
            case "workload" -> headers.put(SubjectTokenFilter.HEADER, identity.workload(FACTS));
            case "audience" -> headers.put(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER, Instant.now().plusSeconds(60),
                    Map.of("aud", List.of("urn:pipesor:api:pipeline")), identity.session));
            case "scope-free" -> headers.put(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER, Instant.now().plusSeconds(60),
                    Map.of("roles", List.of("x")), identity.session));
            case "signature" -> headers.put(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER, Instant.now().plusSeconds(60), Map.of(), identity.pipeline));
            case "expired" -> headers.put(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER, Instant.now().minusSeconds(120), Map.of(), identity.session));
            case "github-user" -> headers.put(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER, Instant.now().plusSeconds(60),
                    Map.of("github_user_id", "43"), identity.session));
            default -> { }
        }
        HttpResponse<String> response;
        var body = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        if (invalid.equals("duplicate")) {
            var builder = HttpRequest.newBuilder(base.resolve(ACCESS_PATH)).header("X-Correlation-Id", "corr-http")
                    .header("Authorization", "Bearer " + identity.workload(FACTS)).header("Content-Type", "application/json")
                    .header(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER)).header(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } else {
            response = request(base, ACCESS_PATH, identity.workload(FACTS), body, headers);
        }
        assertEquals(401, response.statusCode(), response.body());
        assertEquals(0, PROVIDER.reads);
    }

    @Test void routesAcceptOnlyTheirCatalogCallersAndRoles() throws Exception {
        var body = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        var subject = Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER));
        assertEquals(401, request(base, SELF_PATH, null, null, Map.of()).statusCode());
        assertEquals(403, request(base, SELF_PATH, identity.workload(FACTS), null, Map.of()).statusCode());
        assertEquals(403, request(base, ACCESS_PATH, identity.workload("Pipeline.MachineScope.Check"), body, subject).statusCode());
        assertEquals(401, request(base, ACCESS_PATH, identity.session(GITHUB_USER), body, subject).statusCode());
        assertEquals(401, request(base, ACCESS_PATH, identity.session(GITHUB_USER), body, Map.of()).statusCode());
        assertEquals(403, request(base, SELF_PATH, identity.session(GITHUB_USER, Instant.now().plusSeconds(60),
                Map.of("scp", "Pipeline.Access"), identity.session), null, Map.of()).statusCode());
        assertEquals(401, request(base, SELF_PATH, identity.session(GITHUB_USER), null, subject).statusCode());
        assertEquals(0, PROVIDER.reads);
    }

    @Test void rejectsCrossSubjectBodyIdentityAndAmbientHeaders() throws Exception {
        var subject = Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER));
        assertEquals(403, request(base, ACCESS_PATH, identity.workload(FACTS), access("VIEW", RECIPIENT, List.of("823001"), Instant.now().plusSeconds(30)), subject).statusCode());
        var valid = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        var crossed = valid.substring(0, valid.length() - 1) + ",\"tenantId\":\"" + UUID.randomUUID() + "\"}";
        assertEquals(400, request(base, ACCESS_PATH, identity.workload(FACTS), crossed, subject).statusCode());
        assertEquals(400, request(base, SELF_PATH, identity.session(GITHUB_USER), null, Map.of("X-Actor-Id", RECIPIENT.toString())).statusCode());
        var duplicate = HttpRequest.newBuilder(base.resolve(SELF_PATH)).header("Authorization", "Bearer " + identity.session(GITHUB_USER))
                .header("Authorization", "Bearer " + identity.session(GITHUB_USER)).GET().build();
        assertEquals(400, HttpClient.newHttpClient().send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, PROVIDER.reads);
    }

    @Test void cookiesNeverSupplyIdentity() throws Exception {
        String body = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        var cookieRequest = request(base, ACCESS_PATH, identity.workload(FACTS), body,
                Map.of("Cookie", "session=synthetic", SubjectTokenFilter.HEADER, identity.session(GITHUB_USER)));
        assertEquals(200, cookieRequest.statusCode(), cookieRequest.body());
        int reads = PROVIDER.reads;
        assertEquals(401, request(base, SELF_PATH, null, null, Map.of("Cookie", "session=synthetic")).statusCode());
        assertEquals(401, request(base, ACCESS_PATH, null, body, Map.of("Cookie", "session=synthetic")).statusCode());
        assertEquals(reads, PROVIDER.reads);
    }

    @Test void rejectsMalformedUnboundedAndMismatchedRequestBindings() throws Exception {
        var subject = Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER));
        String bearer = identity.workload(FACTS);
        for (String body : List.of("{", access("VIEW", null, List.of("823001", "823001"), Instant.now().plusSeconds(30)),
                access("VIEW", null, List.of(), Instant.now().plusSeconds(30)), access("DISCOVER", null, List.of("823001"), Instant.now().plusSeconds(30)))) {
            assertEquals(400, request(base, ACCESS_PATH, bearer, body, subject).statusCode());
        }
        var crossed = new HashMap<>(subject); crossed.put("X-Correlation-Id", "crossed");
        assertEquals(400, request(base, ACCESS_PATH, bearer, access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)), crossed).statusCode());
        assertEquals(503, request(base, ACCESS_PATH, bearer, access("VIEW", null, List.of("823001"), Instant.now().minusSeconds(1)), subject).statusCode());
        assertEquals(400, request(base, ACCESS_PATH, bearer, " ".repeat(17_000), subject).statusCode());
        assertEquals(0, PROVIDER.reads);
    }

    @Test void failsClosedForUnboundResolverLegacyGenerationAndMidRequestRelink() throws Exception {
        var subject = Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER));
        String body = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        PROVIDER.badSelection = true;
        assertEquals(503, request(base, ACCESS_PATH, identity.workload(FACTS), body, subject).statusCode());
        PROVIDER.badSelection = false; PROVIDER.missingGeneration = true;
        assertEquals(503, request(base, ACCESS_PATH, identity.workload(FACTS), body, subject).statusCode());
        PROVIDER.missingGeneration = false; PROVIDER.changeGeneration = true;
        assertEquals(409, request(base, ACCESS_PATH, identity.workload(FACTS), body, subject).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECTION_REQUIRED", "DENIED", "UNAVAILABLE", "SECRET"})
    void mapsProviderFailuresToFixedProblemsWithoutSecretExposure(String failure, CapturedOutput output) throws Exception {
        PROVIDER.failure = failure;
        var response = request(base, ACCESS_PATH, identity.workload(FACTS), access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)),
                Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER)));
        int expected = switch (failure) { case "CONNECTION_REQUIRED" -> 409; case "DENIED" -> 403; default -> 503; };
        assertEquals(expected, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
        assertFalse(response.body().contains("private-provider-secret"));
        assertFalse(output.getAll().contains("private-provider-secret"));
        if (expected == 409) assertEquals("CONNECTION_REQUIRED", JSON.readTree(response.body()).get("code").asString());
    }

    @Test void servesVerifiedWorkflowMetadataAndBoundedDiscoveryWithoutSilentTruncation() throws Exception {
        String body = JSON.writeValueAsString(Map.of("requestId", "request-1", "correlationId", "corr-http", "deadline", Instant.now().plusSeconds(30).toString(),
                "operation", "REGISTER", "repositoryId", "823001", "workflowId", "12345"));
        var selected = request(base, "/internal/v1/github/workflow-verifications", identity.workload(FACTS), body,
                Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER)));
        assertEquals(200, selected.statusCode(), selected.body());
        assertEquals("build.yml", JSON.readTree(selected.body()).get("workflowFile").asString());
        assertTrue(JSON.readTree(selected.body()).get("defaultBranch").isNull());
        var session = identity.session(GITHUB_USER);
        assertEquals(200, request(base, SELF_PATH, session, null, Map.of()).statusCode());
        assertEquals(200, request(base, "/api/v1/github/repositories/823001/workflows?limit=5", session, null, Map.of()).statusCode());
        assertEquals(400, request(base, SELF_PATH + "&cursor=forged", session, null, Map.of()).statusCode());
        assertEquals(400, request(base, "/api/v1/github/repositories?limit=999", session, null, Map.of()).statusCode());
        PROVIDER.extraRepositories = true;
        var oversized = request(base, "/api/v1/github/repositories?limit=1", session, null, Map.of());
        assertEquals(503, oversized.statusCode());
        assertFalse(oversized.body().contains("example/service"));
    }

    @Test void listsFailedRunsWithOpaqueBoundedPaging() throws Exception {
        var session = identity.session(GITHUB_USER);
        var path = "/api/v1/github/repositories/823001/workflows/12345/runs?conclusion=failure";
        var response = request(base, path, session, null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        var page = JSON.readTree(response.body());
        assertEquals("7001", page.get("items").get(0).get("runId").asString());
        assertEquals(2, page.get("items").get(0).get("attempt").asInt());
        assertEquals("https://github.com/example/service/actions/runs/7001", page.get("items").get(0).get("htmlUrl").asString());
        var next = page.get("nextPageToken").asString();
        assertEquals(200, request(base, path + "&pageToken=" + next + "&pageSize=5", session, null, Map.of()).statusCode());
        // Successful runs are listable as verification candidates; other conclusions are not.
        assertEquals(200, request(base, path.replace("failure", "success"), session, null, Map.of()).statusCode());
        assertEquals(400, request(base, path.replace("failure", "cancelled"), session, null, Map.of()).statusCode());
        assertEquals(400, request(base, path + "&pageSize=101", session, null, Map.of()).statusCode());
        assertEquals(400, request(base, path + "&pageToken=forged", session, null, Map.of()).statusCode());
        assertEquals(403, request(base, path, identity.workload(FACTS), null, Map.of()).statusCode());
    }

    @Test void discoveryPreservesVerifiedGenerationContextAndObservationAndRejectsUnboundOrChangedLinks() throws Exception {
        for (String path : List.of(SELF_PATH, "/api/v1/github/repositories/823001/workflows?limit=5")) {
            var response = request(base, path, identity.session(GITHUB_USER), null, Map.of());
            assertEquals(200, response.statusCode(), response.body());
            var binding = JSON.readTree(response.body()).get("context");
            assertEquals(GENERATION.toString(), binding.get("generationId").asString());
            assertEquals("revision-4", binding.get("revision").asString());
            assertEquals("corr-http", binding.get("correlationId").asString());
            assertEquals(ACTOR.toString(), binding.get("actorObjectId").asString());
            assertEquals(ACTOR.toString(), binding.get("subjectObjectId").asString());
            assertEquals("DISCOVER", binding.get("operation").asString());
            PROVIDER.missingGeneration = true;
            assertEquals(503, request(base, path, identity.session(GITHUB_USER), null, Map.of()).statusCode());
            PROVIDER.missingGeneration = false; PROVIDER.changeGeneration = true; PROVIDER.linkReads = 0;
            assertEquals(409, request(base, path, identity.session(GITHUB_USER), null, Map.of()).statusCode());
            PROVIDER.changeGeneration = false; PROVIDER.linkReads = 0;
        }
    }

    @Test void connectionStatusAndDisconnectUseOnlySelfIdentityAndExpectedRevision() throws Exception {
        var status = request(base, "/api/v1/github/connection", identity.session(GITHUB_USER), null, Map.of());
        assertEquals(200, status.statusCode());
        assertEquals("\"7\"", status.headers().firstValue("ETag").orElseThrow());
        assertEquals(new ConnectionIdentity(TENANT, ACTOR), PROVIDER.statusIdentity);
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/github/connection")).DELETE()
                .header("Authorization", "Bearer " + identity.session(GITHUB_USER)).header("If-Match", "\"7\"").build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        verify(context.getBean(GitHubConnections.class)).disconnect(new org.pipesor.ingestion.github.application.commands.ConnectionCommands.Disconnect(CONNECTION, 7));
        assertFalse(JSON.readTree(response.body()).get("locallyLinked").asBoolean());
    }

    @Test void refusesFactsAfterBudgetElapsedDuringProviderIo() throws Exception {
        PROVIDER.delay = true;
        var response = request(base, ACCESS_PATH, identity.workload(FACTS), access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(100)),
                Map.of(SubjectTokenFilter.HEADER, identity.session(GITHUB_USER)));
        assertEquals(503, response.statusCode(), response.body());
        assertFalse(response.body().contains("github-user"));
        CLOCK.offset = Duration.ZERO;
    }

    @Test void refusesResponseAboveConfiguredByteLimit() throws Exception {
        try (var bounded = application("pipesor.api.limits.max-response-bytes=100").run()) {
            var response = request(base(bounded), SELF_PATH, identity.session(GITHUB_USER), null, Map.of());
            assertEquals(503, response.statusCode(), response.body());
            assertFalse(response.body().contains("example/service"));
        }
    }

    @Test void publishesJwksAndGeneratesOpenApiFromActualRoutesWithoutSwagger() throws Exception {
        var jwks = request(base, JwksController.PATH, null, null, Map.of());
        assertEquals(200, jwks.statusCode());
        assertTrue(jwks.headers().firstValue("Cache-Control").orElseThrow().contains("max-age=300"));
        assertFalse(jwks.body().contains("\"d\""));
        var response = request(base, "/v3/api-docs", null, null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        var schema = JSON.readTree(response.body());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi.json"), response.body());
        assertTrue(schema.get("paths").has(ACCESS_PATH));
        assertTrue(schema.get("paths").has("/api/v1/github/repositories/{repositoryId}/workflows/{workflowId}/runs"));
        assertTrue(schema.get("components").get("securitySchemes").has("platformBearer"));
        assertEquals(401, request(base, "/swagger-ui/index.html", null, null, Map.of()).statusCode());
    }

    @Test void frameworkMetadataAndLogoutDoNotCreatePublicEndpoints() throws Exception {
        for (var path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/v1/github", "/logout", "/actuator/env")) {
            assertEquals(401, request(base, path, null, null, Map.of()).statusCode(), path);
        }
    }

    @Test void missingPlatformIdentityFailsBeforeAListenerCanServe() {
        var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(HttpApplication.class).profiles("local")
                .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.root=OFF",
                        "management.endpoint.health.group.readiness.include=readinessState").run().close());
        Throwable root = failure;
        while (root.getCause() != null) { root = root.getCause(); }
        assertInstanceOf(IllegalArgumentException.class, root);
    }

    private static SpringApplicationBuilder application(String... extra) {
        var properties = new ArrayList<>(identity.properties(keys));
        properties.addAll(List.of("server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off", "logging.level.root=WARN",
                "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=false",
                "pipesor.api.limits.request-timeout=30s", "pipesor.api.limits.max-batch-size=5",
                "pipesor.api.limits.max-page-size=5", "pipesor.api.limits.max-request-bytes=16384",
                "pipesor.api.limits.max-response-bytes=1000000", "pipesor.api.limits.max-context-length=128"));
        properties.addAll(List.of(extra));
        return new SpringApplicationBuilder(HttpApplication.class).profiles("local")
                .initializers(c -> org.springframework.boot.test.util.TestPropertyValues.of(properties.toArray(String[]::new)).applyTo(c));
    }
    private static URI base(ConfigurableApplicationContext application) { return URI.create("http://localhost:" + ((WebServerApplicationContext) application).getWebServer().getPort()); }
    private static HttpResponse<String> request(URI uri, String path, String bearer, String body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(uri.resolve(path)).header("X-Correlation-Id", headers.getOrDefault("X-Correlation-Id", "corr-http"));
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        headers.forEach((name, value) -> { if (!name.equals("X-Correlation-Id")) { request.header(name, value); } });
        if (body == null) { request.GET(); } else { request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)); }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String access(String operation, UUID recipient, List<String> ids, Instant deadline) {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", "request-1"); body.put("correlationId", "corr-http"); body.put("deadline", deadline.toString());
        body.put("operation", operation); body.put("repositoryIds", ids);
        if (recipient != null) { body.put("recipientObjectId", recipient.toString()); }
        return JSON.writeValueAsString(body);
    }
    private static <Q, T> Observation<Q, T> observed(Q request, T value) { return new Observation<>(request, value, CLOCK.instant()); }
    private static final class MutableClock extends Clock {
        volatile Duration offset = Duration.ZERO;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }
    private static final class SyntheticProvider implements ReadOnlyGitHub {
        String failure; boolean denied; boolean badSelection; boolean missingGeneration; boolean extraRepositories;
        boolean changeGeneration; boolean delay; int linkReads; int reads; AccessContext lastContext; ConnectionIdentity statusIdentity;
        Observation<AccessContext, Optional<GitHubLink>> link(AccessContext request) {
            reads++; lastContext = request; linkReads++;
            return observed(request, Optional.of(new GitHubLink(request.tenantId(), request.subjectRef(), "github-user", "revision-4", Instant.EPOCH,
                    missingGeneration ? null : changeGeneration && linkReads > 1 ? UUID.randomUUID() : GENERATION)));
        }
        Observation<GitHubRepositorySelection.Request, Map<String, Optional<RepositoryTarget>>> resolve(GitHubRepositorySelection.Request request) {
            var result = new LinkedHashMap<String, Optional<RepositoryTarget>>();
            request.repositoryIds().forEach(id -> result.put(id, denied && id.equals("823002") ? Optional.empty() : Optional.of(new RepositoryTarget(id, "5001"))));
            return observed(request, badSelection ? Map.of() : result);
        }
        @Override public Observation<Identity, String> currentUser(Identity request) {
            if (delay) { CLOCK.offset = Duration.ofSeconds(31); }
            if ("SECRET".equals(failure)) { throw new IllegalStateException("private-provider-secret"); }
            if (failure != null) { throw new GitHubAccessFailure(GitHubAccessFailure.Reason.valueOf(failure)); }
            return observed(request, "github-user");
        }
        @Override public Observation<RepositoryRequest, RepositoryFacts> repository(RepositoryRequest request) { return observed(request, facts(request.repository())); }
        @Override public Observation<Identity, List<RepositoryFacts>> repositories(Identity request) {
            return observed(request, extraRepositories ? List.of(facts(new RepositoryTarget("823001", "5001")), facts(new RepositoryTarget("823002", "5001")))
                    : List.of(facts(new RepositoryTarget("823001", "5001"))));
        }
        @Override public Observation<RepositoryRequest, List<WorkflowMetadata>> workflows(RepositoryRequest request) { return observed(request, List.of(workflow(request.repository()))); }
        @Override public Observation<WorkflowRequest, WorkflowMetadata> workflow(WorkflowRequest request) { return observed(request, workflow(request.repository().repository())); }
        @Override public Observation<RunsRequest, RunPage> runs(RunsRequest request) {
            reads++;
            return observed(request, new RunPage(List.of(new WorkflowRun("7001", 2, "failure", "main", "a".repeat(40),
                    Instant.parse("2026-11-20T10:00:00Z"), URI.create("https://github.com/example/service/actions/runs/7001"))), true));
        }
        private WorkflowMetadata workflow(RepositoryTarget target) { return new WorkflowMetadata(target, "12345", "build", "build.yml"); }
        private RepositoryFacts facts(RepositoryTarget target) { return new RepositoryFacts(target, "account-7", true, true, true, true, true, "example/service", null); }
    }
}
