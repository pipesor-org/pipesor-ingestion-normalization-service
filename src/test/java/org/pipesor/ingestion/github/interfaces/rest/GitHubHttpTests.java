package org.pipesor.ingestion.github.interfaces.rest;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.configuration.*;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.queries.*;
import org.pipesor.ingestion.github.application.services.*;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.shared.interfaces.rest.ApiExceptionHandler;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.*;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP, local RSA signatures and the production JWT validators; only integration boundaries are synthetic. */
@ExtendWith(OutputCaptureExtension.class)
class GitHubHttpTests {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RECIPIENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SELF = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PIPELINE = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID GENERATION = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID CONNECTION = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String ISSUER = "https://login.example.invalid/" + TENANT + "/v2.0";
    private static final String AUDIENCE = "api://ingestion-test";
    private static final String SELF_PATH = "/api/v1/github/repositories?limit=5";
    private static final String ACCESS_PATH = "/internal/v1/github/access-verifications";
    private static final KeyPair KEYS = keys();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SyntheticProvider PROVIDER = new SyntheticProvider();
    private static final MutableClock CLOCK = new MutableClock();
    private static ConfigurableApplicationContext context;
    private static URI base;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
    @Import({ApiSecurityConfiguration.class, OpenApiConfiguration.class, GitHubController.class, ApiExceptionHandler.class})
    static class HttpApplication {
        @Bean @Primary Clock testClock() { return CLOCK; }
        @Bean @Primary JwtDecoder signatureDecoder(ApiProperties properties) {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(ApiSecurityConfiguration.validator(properties.security(), Clock.systemUTC()));
            return decoder;
        }
    }
    @Configuration(proxyBeanMethods = false)
    @Import({HttpApplication.class, GitHubConfiguration.class})
    static class BackendApplication {
        @Bean ReadOnlyGitHub provider() { return PROVIDER; }
        @Bean CurrentGitHubLink links() { return PROVIDER::link; }
        @Bean InstallationAdmission admission() { return request -> observed(request, true); }
        @Bean GitHubRepositorySelection selections() { return PROVIDER::resolve; }
        @Bean GitHubConnectionQueries connectionQueries() {
            return query -> { PROVIDER.statusIdentity = query.identity(); return Optional.of(new ConnectionStatus(CONNECTION, 7, GENERATION, Instant.now(), Instant.now())); };
        }
        @Bean GitHubConnections connectionCommands() { return mock(GitHubConnections.class); }
    }

    @BeforeAll static void start() {
        context = application(BackendApplication.class).run();
        base = base(context);
    }
    @AfterAll static void stop() { if (context != null) { context.close(); } }
    @BeforeEach void reset() {
        PROVIDER.failure = null; PROVIDER.denied = false; PROVIDER.badSelection = false;
        PROVIDER.missingGeneration = false; PROVIDER.extraRepositories = false; PROVIDER.reads = 0;
        PROVIDER.changeGeneration = false; PROVIDER.linkReads = 0;
        PROVIDER.delay = false; CLOCK.offset = Duration.ZERO;
        org.mockito.Mockito.reset(context.getBean(GitHubConnections.class));
    }

    @Test void bindsSignedOboActorRecipientGenerationAccountAndRequestValidity() throws Exception {
        var expiry = Instant.now().plusSeconds(10);
        var body = access("GRANT_CONFIGURE", RECIPIENT, List.of("823001", "823002"), Instant.now().plusSeconds(100));
        PROVIDER.denied = true;
        var response = request(base, ACCESS_PATH, token(PIPELINE, expiry, Map.of()), body, Map.of());
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
        assertFalse(response.body().contains("allowed"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aud", "iss", "tid", "oid", "scp", "azp", "ver", "app-only", "expired", "future", "signature"})
    void rejectsInvalidSignedIdentityBeforeProviderReads(String invalid) throws Exception {
        Map<String, Object> claims = new HashMap<>();
        switch (invalid) {
            case "aud" -> claims.put("aud", "api://another-service");
            case "iss" -> claims.put("iss", "https://other.example.invalid");
            case "tid", "azp" -> claims.put(invalid, UUID.randomUUID().toString());
            case "oid" -> claims.put("oid", "invalid");
            case "scp" -> claims.put("scp", "other.scope");
            case "ver" -> claims.put("ver", "1.0");
            case "app-only" -> { claims.put("scp", null); claims.put("idtyp", "app"); claims.put("roles", List.of("application.role")); }
            case "future" -> claims.put("nbf", Date.from(Instant.now().plusSeconds(500)));
            default -> { }
        }
        var expiry = Instant.now().plusSeconds(invalid.equals("expired") ? -120 : 120);
        String bearer = token(PIPELINE, expiry, claims, invalid.equals("signature") ? keys() : KEYS);
        var response = request(base, ACCESS_PATH, bearer, access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)), Map.of());
        assertEquals(401, response.statusCode(), response.body());
        assertEquals(0, PROVIDER.reads);
        assertEquals("UNAUTHENTICATED", JSON.readTree(response.body()).get("code").asString());
        assertFalse(response.body().contains("invalid_token"));
    }

    @Test void rejectsMissingAuthenticationCallerChannelCrossSubjectAndBodyIdentity() throws Exception {
        assertEquals(401, request(base, SELF_PATH, null, null, Map.of()).statusCode());
        assertEquals(403, request(base, SELF_PATH, token(PIPELINE), null, Map.of()).statusCode());
        assertEquals(403, request(base, ACCESS_PATH, token(SELF), access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)), Map.of()).statusCode());
        assertEquals(403, request(base, ACCESS_PATH, token(PIPELINE), access("VIEW", RECIPIENT, List.of("823001"), Instant.now().plusSeconds(30)), Map.of()).statusCode());
        var valid = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        var crossed = valid.substring(0, valid.length() - 1) + ",\"tenantId\":\"" + UUID.randomUUID() + "\"}";
        assertEquals(400, request(base, ACCESS_PATH, token(PIPELINE), crossed, Map.of()).statusCode());
        assertEquals(400, request(base, SELF_PATH, token(SELF), null, Map.of("X-Actor-Id", RECIPIENT.toString())).statusCode());
        assertEquals(0, PROVIDER.reads);
    }

    @Test void cookiesNeverSupplyIdentityAndCannotMutateWithoutABearerToken() throws Exception {
        String body = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        var cookieRequest = request(base, ACCESS_PATH, token(PIPELINE), body, Map.of("Cookie", "session=synthetic"));
        assertEquals(200, cookieRequest.statusCode(), cookieRequest.body());
        assertEquals(ACTOR.toString(), JSON.readTree(cookieRequest.body()).get("actorObjectId").asString());
        int reads = PROVIDER.reads;
        assertEquals(401, request(base, SELF_PATH, null, null, Map.of("Cookie", "session=synthetic")).statusCode());
        assertEquals(403, request(base, ACCESS_PATH, null, body, Map.of("Cookie", "session=synthetic")).statusCode());
        assertEquals(reads, PROVIDER.reads);
    }

    @Test void rejectsMalformedUnboundedAndMismatchedRequestBindings() throws Exception {
        String bearer = token(PIPELINE);
        for (String body : List.of("{", access("VIEW", null, List.of("823001", "823001"), Instant.now().plusSeconds(30)),
                access("VIEW", null, List.of(), Instant.now().plusSeconds(30)), access("DISCOVER", null, List.of("823001"), Instant.now().plusSeconds(30)))) {
            assertEquals(400, request(base, ACCESS_PATH, bearer, body, Map.of()).statusCode());
        }
        assertEquals(400, request(base, ACCESS_PATH, bearer, access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)), Map.of("X-Correlation-Id", "crossed")).statusCode());
        assertEquals(503, request(base, ACCESS_PATH, bearer, access("VIEW", null, List.of("823001"), Instant.now().minusSeconds(1)), Map.of()).statusCode());
        assertEquals(400, request(base, ACCESS_PATH, bearer, " ".repeat(17_000), Map.of()).statusCode());
        assertEquals(0, PROVIDER.reads);
    }

    @Test void failsClosedForUnboundResolverLegacyGenerationAndMidRequestRelink() throws Exception {
        String body = access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30));
        PROVIDER.badSelection = true;
        assertEquals(503, request(base, ACCESS_PATH, token(PIPELINE), body, Map.of()).statusCode());
        PROVIDER.badSelection = false; PROVIDER.missingGeneration = true;
        assertEquals(503, request(base, ACCESS_PATH, token(PIPELINE), body, Map.of()).statusCode());
        PROVIDER.missingGeneration = false; PROVIDER.changeGeneration = true;
        assertEquals(409, request(base, ACCESS_PATH, token(PIPELINE), body, Map.of()).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONNECTION_REQUIRED", "DENIED", "UNAVAILABLE", "SECRET"})
    void mapsProviderFailuresToFixedProblemsWithoutSecretExposure(String failure, CapturedOutput output) throws Exception {
        PROVIDER.failure = failure;
        var response = request(base, ACCESS_PATH, token(PIPELINE), access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)), Map.of());
        int expected = switch (failure) { case "CONNECTION_REQUIRED" -> 409; case "DENIED" -> 403; default -> 503; };
        assertEquals(expected, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
        assertFalse(response.body().contains("private-provider-secret"));
        assertFalse(output.getAll().contains("private-provider-secret"));
    }

    @Test void servesVerifiedWorkflowMetadataAndBoundedDiscoveryWithoutSilentTruncation() throws Exception {
        String body = JSON.writeValueAsString(Map.of("requestId", "request-1", "correlationId", "corr-http", "deadline", Instant.now().plusSeconds(30).toString(),
                "operation", "REGISTER", "repositoryId", "823001", "workflowId", "12345"));
        var selected = request(base, "/internal/v1/github/workflow-verifications", token(PIPELINE), body, Map.of());
        assertEquals(200, selected.statusCode(), selected.body());
        assertEquals("build.yml", JSON.readTree(selected.body()).get("workflowFile").asString());
        assertTrue(JSON.readTree(selected.body()).get("defaultBranch").isNull());
        assertEquals(200, request(base, SELF_PATH, token(SELF), null, Map.of()).statusCode());
        assertEquals(200, request(base, "/api/v1/github/repositories/823001/workflows?limit=5", token(SELF), null, Map.of()).statusCode());
        assertEquals(400, request(base, SELF_PATH + "&cursor=forged", token(SELF), null, Map.of()).statusCode());
        assertEquals(400, request(base, "/api/v1/github/repositories?limit=999", token(SELF), null, Map.of()).statusCode());
        PROVIDER.extraRepositories = true;
        var oversized = request(base, "/api/v1/github/repositories?limit=1", token(SELF), null, Map.of());
        assertEquals(503, oversized.statusCode());
        assertFalse(oversized.body().contains("example/service"));
    }

    @Test void discoveryPreservesVerifiedGenerationContextAndObservationAndRejectsUnboundOrChangedLinks() throws Exception {
        for (String path : List.of(SELF_PATH, "/api/v1/github/repositories/823001/workflows?limit=5")) {
            var response = request(base, path, token(SELF), null, Map.of());
            assertEquals(200, response.statusCode(), response.body());
            var binding = JSON.readTree(response.body()).get("context");
            assertEquals(GENERATION.toString(), binding.get("generationId").asString());
            assertEquals("revision-4", binding.get("revision").asString());
            assertEquals("corr-http", binding.get("correlationId").asString());
            assertEquals(ACTOR.toString(), binding.get("actorObjectId").asString());
            assertEquals(ACTOR.toString(), binding.get("subjectObjectId").asString());
            assertEquals("DISCOVER", binding.get("operation").asString());
            assertFalse(Instant.parse(binding.get("observedAt").asString()).isAfter(Instant.parse(binding.get("validUntil").asString())));
            PROVIDER.missingGeneration = true;
            assertEquals(503, request(base, path, token(SELF), null, Map.of()).statusCode());
            PROVIDER.missingGeneration = false; PROVIDER.changeGeneration = true; PROVIDER.linkReads = 0;
            assertEquals(409, request(base, path, token(SELF), null, Map.of()).statusCode());
            PROVIDER.changeGeneration = false; PROVIDER.linkReads = 0;
        }
    }

    @Test void connectionStatusAndDisconnectUseOnlySelfIdentityAndExpectedRevision() throws Exception {
        var status = request(base, "/api/v1/github/connection", token(SELF), null, Map.of());
        assertEquals(200, status.statusCode());
        assertEquals("\"7\"", status.headers().firstValue("ETag").orElseThrow());
        assertEquals(new ConnectionIdentity(TENANT, ACTOR), PROVIDER.statusIdentity);
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/github/connection")).DELETE()
                .header("Authorization", "Bearer " + token(SELF)).header("If-Match", "\"7\"").build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        verify(context.getBean(GitHubConnections.class)).disconnect(new org.pipesor.ingestion.github.application.commands.ConnectionCommands.Disconnect(CONNECTION, 7));
        assertFalse(JSON.readTree(response.body()).get("locallyLinked").asBoolean());
        assertEquals("NOT_CONFIRMED", JSON.readTree(response.body()).get("cleanupStatus").asString());
    }

    @Test void missingProductionPortsReturnUnavailableInsteadOfEmptySuccess() throws Exception {
        try (var unavailable = application(HttpApplication.class).run()) {
            var uri = base(unavailable);
            assertEquals(503, request(uri, SELF_PATH, token(SELF), null, Map.of()).statusCode());
            assertEquals(503, request(uri, "/api/v1/github/connection", token(SELF), null, Map.of()).statusCode());
            assertEquals(503, request(uri, ACCESS_PATH, token(PIPELINE), access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(30)), Map.of()).statusCode());
        }
    }

    @Test void refusesFactsAfterBudgetElapsedDuringProviderIo() throws Exception {
        PROVIDER.delay = true;
        var response = request(base, ACCESS_PATH, token(PIPELINE), access("VIEW", null, List.of("823001"), Instant.now().plusSeconds(100)), Map.of());
        assertEquals(503, response.statusCode(), response.body());
        assertFalse(response.body().contains("github-user"));
        CLOCK.offset = Duration.ZERO;
    }

    @Test void refusesResponseAboveConfiguredByteLimit() throws Exception {
        try (var bounded = application(BackendApplication.class).properties("ingestion.api.limits.max-response-bytes=100").run()) {
            var response = request(base(bounded), SELF_PATH, token(SELF), null, Map.of());
            assertEquals(503, response.statusCode(), response.body());
            assertFalse(response.body().contains("example/service"));
        }
    }

    @Test void generatesProtectedOpenApiFromActualRoutesWithoutSwaggerOrCallback() throws Exception {
        assertEquals(401, request(base, "/v3/api-docs", null, null, Map.of()).statusCode());
        assertEquals(403, request(base, "/v3/api-docs", token(SELF), null, Map.of()).statusCode());
        var response = request(base, "/v3/api-docs", token(PIPELINE), null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        var schema = JSON.readTree(response.body());
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/openapi.json"), response.body());
        assertEquals(5, schema.get("paths").size());
        assertTrue(schema.get("paths").has(ACCESS_PATH));
        assertFalse(response.body().contains("/callback"));
        assertTrue(schema.get("components").get("securitySchemes").has("entraDelegated"));
        assertEquals(401, request(base, "/swagger-ui/index.html", token(PIPELINE), null, Map.of()).statusCode());
    }

    @Test void frameworkMetadataAndLogoutDoNotCreatePublicEndpoints() throws Exception {
        for (var path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/api/v1/github", "/logout")) {
            assertEquals(401, request(base, path, null, null, Map.of()).statusCode(), path);
        }
    }

    @Test void missingSecurityConfigurationFailsBeforeAListenerCanServe() {
        new WebApplicationContextRunner().withUserConfiguration(HttpApplication.class)
                .withPropertyValues("server.port=0")
                .run(application -> {
                    Throwable failure = application.getStartupFailure();
                    assertNotNull(failure);
                    while (failure.getCause() != null) { failure = failure.getCause(); }
                    assertEquals("API security configuration required", failure.getMessage());
                });
    }

    private static SpringApplicationBuilder application(Class<?> source) {
        return new SpringApplicationBuilder(source).initializers(application -> org.springframework.boot.test.util.TestPropertyValues
                .of("server.port=0", "springdoc.api-docs.enabled=true").applyTo(application)).properties("spring.main.banner-mode=off",
                "logging.level.root=WARN", "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=false",
                "ingestion.api.security.issuer=" + ISSUER, "ingestion.api.security.jwk-set-uri=https://keys.example.invalid/jwks",
                "ingestion.api.security.tenant=" + TENANT, "ingestion.api.security.audience=" + AUDIENCE,
                "ingestion.api.security.token-version=2.0", "ingestion.api.security.self-scope=github.self",
                "ingestion.api.security.internal-scope=github.verify", "ingestion.api.security.self-callers=" + SELF,
                "ingestion.api.security.pipeline-callers=" + PIPELINE, "ingestion.api.security.clock-skew=0s",
                "ingestion.api.limits.request-timeout=30s", "ingestion.api.limits.max-batch-size=5",
                "ingestion.api.limits.max-page-size=5", "ingestion.api.limits.max-request-bytes=16384",
                "ingestion.api.limits.max-response-bytes=1000000", "ingestion.api.limits.max-context-length=128");
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
    private static String token(UUID caller) throws Exception { return token(caller, Instant.now().plusSeconds(120), Map.of()); }
    private static String token(UUID caller, Instant expiry, Map<String, Object> changes) throws Exception { return token(caller, expiry, changes, KEYS); }
    private static String token(UUID caller, Instant expiry, Map<String, Object> changes, KeyPair keys) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).issueTime(Date.from(Instant.now().minusSeconds(1)))
                .expirationTime(Date.from(expiry)).claim("tid", TENANT.toString()).claim("oid", ACTOR.toString()).claim("ver", "2.0")
                .claim("azp", caller.toString()).claim("scp", caller.equals(SELF) ? "github.self" : "github.verify");
        changes.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(keys.getPrivate())); return jwt.serialize();
    }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
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
        private WorkflowMetadata workflow(RepositoryTarget target) { return new WorkflowMetadata(target, "12345", "build", "build.yml"); }
        private RepositoryFacts facts(RepositoryTarget target) { return new RepositoryFacts(target, "account-7", true, true, true, true, true, "example/service", null); }
    }
}
