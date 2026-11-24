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
import tools.jackson.databind.JsonNode;
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
    private static final String REPOSITORIES = "/api/v1/github/repositories";
    private static final String WORKFLOWS = "/api/v1/github/repositories/823001/workflows";
    private static final String ACCESS_PATH = "/internal/v1/github/access-verifications";
    private static final String FACTS = "Pipeline.GitHubFacts.Read";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SyntheticProvider PROVIDER = new SyntheticProvider();
    private static final List<String> ADMITTED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final org.pipesor.ingestion.github.application.ports.RepositoryAdmissions ADMISSIONS =
            new org.pipesor.ingestion.github.application.ports.RepositoryAdmissions() {
        @Override public boolean admitByAdministrator(Admission admission) { return ADMITTED.add(admission.repositoryId()); }
        @Override public List<String> revokeAbsent(Revocation revocation) { return List.of(); }
    };
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
        @Bean GitHubQueries queries() { return new GitHubQueryHandler(PROVIDER::link, request -> observed(request, true), ADMISSIONS, PROVIDER, CLOCK); }
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
        PROVIDER.repositoryList = null; PROVIDER.workflowList = null; PROVIDER.installationReads = 0;
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
        assertTrue(page.get("items").get(0).has("runStartedAt"), "runStartedAt is part of the run listing contract");
        var next = page.get("nextPageToken").asString();
        assertEquals(200, request(base, path + "&pageToken=" + next + "&pageSize=5", session, null, Map.of()).statusCode());
        // Successful runs are listable as verification candidates; other conclusions are not.
        assertEquals(200, request(base, path.replace("failure", "success"), session, null, Map.of()).statusCode());
        assertEquals(400, request(base, path.replace("failure", "cancelled"), session, null, Map.of()).statusCode());
        assertEquals(400, request(base, path + "&pageSize=101", session, null, Map.of()).statusCode());
        assertEquals(400, request(base, path + "&pageToken=forged", session, null, Map.of()).statusCode());
        assertEquals(403, request(base, path, identity.workload(FACTS), null, Map.of()).statusCode());
    }

    @Test void installationsListRepositoryAdmissionForTheDashboard() throws Exception {
        var response = request(base, "/api/v1/github/installations", identity.session(GITHUB_USER), null, Map.of());
        assertEquals(200, response.statusCode(), response.body());
        var installation = JSON.readTree(response.body()).get("installations").get(0);
        assertEquals("5001", installation.get("installationId").asString());
        assertEquals("example", installation.get("account").get("login").asString());
        assertFalse(installation.get("suspended").asBoolean());
        var repository = installation.get("repositories").get(0);
        assertEquals("823001", repository.get("repositoryId").asString());
        for (var field : List.of("fullName", "admitted", "userIsAdmin")) assertTrue(repository.has(field), field);
        assertEquals(403, request(base, "/api/v1/github/installations", identity.workload(FACTS), null, Map.of()).statusCode());
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

    @Test void pagesMoreThanOneHundredRepositoriesInCaseInsensitiveThenNumericOrder() throws Exception {
        PROVIDER.repositoryList = manyRepositories();
        var pages = pages(REPOSITORIES + "?pageSize=100", "repositories");
        assertEquals(2, pages.size());
        assertEquals(100, pages.get(0).get("repositories").size());
        assertEquals(50, pages.get(1).get("repositories").size());
        assertTrue(pages.get(1).get("nextPageToken").isNull());
        assertEquals(expectedRepositoryOrder(), ids(pages, "repositories", "repositoryId"));
        assertEquals(2, PROVIDER.installationReads, "every page repeats the ADR-024 reconciliation");
        var first = pages.get(0).get("context");
        for (var page : pages) {
            var context = page.get("context");
            assertEquals(fields(first), fields(context));
            for (var field : List.of("tenantId", "actorObjectId", "subjectObjectId", "operation", "githubUserId", "generationId", "revision")) {
                assertEquals(first.get(field).asString(), context.get(field).asString(), field);
            }
        }
        var legacy = request(base, REPOSITORIES + "?limit=100", identity.session(GITHUB_USER), null, Map.of());
        assertEquals(503, legacy.statusCode(), legacy.body());
        assertEquals("VERIFICATION_UNAVAILABLE", JSON.readTree(legacy.body()).get("code").asString());
    }

    @Test void filtersRepositoriesCaseInsensitivelyAndPagesUnderTheFilter() throws Exception {
        PROVIDER.repositoryList = manyRepositories();
        var pages = pages(REPOSITORIES + "?pageSize=20&q=" + query(" REPO-1 "), "repositories");
        assertEquals(List.of(20, 20, 8), pages.stream().map(page -> page.get("repositories").size()).toList());
        var expected = expectedRepositoryOrder().stream().filter(id -> id.length() == 6 && 900147 - Integer.parseInt(id) >= 100).toList();
        assertEquals(expected, ids(pages, "repositories", "repositoryId"));
        for (var page : pages) {
            for (var repository : page.get("repositories")) {
                assertTrue(repository.get("fullName").asString().toLowerCase(Locale.ROOT).contains("repo-1"), repository.toString());
            }
        }
        var token = pages.get(0).get("nextPageToken").asString();
        var normalized = request(base, REPOSITORIES + "?pageSize=20&q=repo-1&pageToken=" + token, identity.session(GITHUB_USER), null, Map.of());
        assertEquals(200, normalized.statusCode(), "the token binds the trimmed, case-insensitive q");
        var none = JSON.readTree(request(base, REPOSITORIES + "?pageSize=20&q=absent", identity.session(GITHUB_USER), null, Map.of()).body());
        assertEquals(0, none.get("repositories").size());
        assertTrue(none.get("nextPageToken").isNull());
        assertEquals(200, request(base, REPOSITORIES + "?pageSize=1&q=" + "x".repeat(100), identity.session(GITHUB_USER), null, Map.of()).statusCode());
        assertEquals(200, request(base, REPOSITORIES + "?pageSize=1&q=xy", identity.session(GITHUB_USER), null, Map.of()).statusCode());
    }

    @Test void pagesWorkflowsAndFiltersOnNameOrFile() throws Exception {
        var workflows = new ArrayList<WorkflowMetadata>();
        var target = new RepositoryTarget("823001", "5001");
        workflows.add(new WorkflowMetadata(target, "77", "Deploy production", ".github/workflows/RELEASE.yml"));
        for (int index = 1; index < 150; index++) {
            workflows.add(new WorkflowMetadata(target, Integer.toString(5000 + index), (index % 2 == 0 ? "Build " : "build ")
                    + String.format("%03d", 149 - index), ".github/workflows/w" + index + ".yml"));
        }
        PROVIDER.workflowList = List.copyOf(workflows);
        var pages = pages(WORKFLOWS + "?pageSize=100", "workflows");
        assertEquals(List.of(100, 50), pages.stream().map(page -> page.get("workflows").size()).toList());
        var expected = new ArrayList<String>();
        for (int index = 149; index >= 1; index--) { expected.add(Integer.toString(5000 + index)); }
        expected.add("77");
        assertEquals(expected, ids(pages, "workflows", "workflowId"));
        for (var page : pages) {
            assertEquals("823001", page.get("repository").get("repositoryId").asString());
            assertEquals("DISCOVER", page.get("context").get("operation").asString());
        }
        var byFile = pages(WORKFLOWS + "?pageSize=5&q=release", "workflows");
        assertEquals(List.of("77"), ids(byFile, "workflows", "workflowId"));
        var byName = pages(WORKFLOWS + "?pageSize=4&q=BUILD%2014", "workflows");
        assertEquals(List.of(4, 4, 1), byName.stream().map(page -> page.get("workflows").size()).toList());
        assertEquals(List.of("5009", "5008", "5007", "5006", "5005", "5004", "5003", "5002", "5001"), ids(byName, "workflows", "workflowId"));
        var legacy = request(base, WORKFLOWS + "?limit=100", identity.session(GITHUB_USER), null, Map.of());
        assertEquals(503, legacy.statusCode(), legacy.body());
    }

    @Test void rejectsMalformedAndMisboundPageTokensBeforeProviderReads() throws Exception {
        PROVIDER.repositoryList = manyRepositories();
        var session = identity.session(GITHUB_USER);
        var repositoryToken = JSON.readTree(request(base, REPOSITORIES + "?pageSize=5&q=repo", session, null, Map.of()).body()).get("nextPageToken").asString();
        var unfiltered = JSON.readTree(request(base, REPOSITORIES + "?pageSize=5", session, null, Map.of()).body()).get("nextPageToken").asString();
        PROVIDER.workflowList = List.of(new WorkflowMetadata(new RepositoryTarget("823001", "5001"), "1", "build", "build.yml"),
                new WorkflowMetadata(new RepositoryTarget("823001", "5001"), "2", "test", "test.yml"));
        var workflowToken = JSON.readTree(request(base, WORKFLOWS + "?pageSize=1", session, null, Map.of()).body()).get("nextPageToken").asString();
        assertTrue(repositoryToken.length() <= 512 && repositoryToken.matches("[A-Za-z0-9_-]+"), repositoryToken);
        PROVIDER.reads = 0; PROVIDER.installationReads = 0;
        var flipped = (repositoryToken.charAt(5) == 'A' ? 'B' : 'A');
        var rejected = List.of(
                REPOSITORIES + "?pageSize=5&q=repo-&pageToken=" + repositoryToken,
                REPOSITORIES + "?pageSize=5&pageToken=" + repositoryToken,
                REPOSITORIES + "?pageSize=5&q=repo&pageToken=" + unfiltered,
                REPOSITORIES + "?pageSize=5&pageToken=" + workflowToken,
                "/api/v1/github/repositories/823002/workflows?pageSize=1&pageToken=" + workflowToken,
                WORKFLOWS + "?pageSize=1&pageToken=" + unfiltered,
                REPOSITORIES + "?pageSize=5&q=repo&pageToken=" + repositoryToken.substring(0, 5) + flipped + repositoryToken.substring(6),
                REPOSITORIES + "?pageSize=5&q=repo&pageToken=" + repositoryToken + "A",
                REPOSITORIES + "?pageSize=5&q=repo&pageToken=" + repositoryToken + "%3D",
                REPOSITORIES + "?pageSize=5&pageToken=forged",
                REPOSITORIES + "?pageSize=5&pageToken=",
                REPOSITORIES + "?pageSize=5&pageToken=" + "A".repeat(513),
                WORKFLOWS + "?pageSize=1&pageToken=%21%21");
        for (var path : rejected) {
            var response = request(base, path, session, null, Map.of());
            assertEquals(400, response.statusCode(), path);
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
            assertEquals("INVALID_PAGE_TOKEN", JSON.readTree(response.body()).get("code").asString(), path);
        }
        assertEquals(0, PROVIDER.reads);
        assertEquals(0, PROVIDER.installationReads);
        assertEquals(200, request(base, REPOSITORIES + "?pageSize=5&q=repo&pageToken=" + repositoryToken, session, null, Map.of()).statusCode());
        assertEquals(200, request(base, WORKFLOWS + "?pageSize=1&pageToken=" + workflowToken, session, null, Map.of()).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?limit=5&pageSize=5", "?limit=5&q=ab", "?limit=5&pageToken=abc", "?q=ab", "?pageToken=abc",
            "?pageSize=5&cursor=x", "?limit=5&cursor=", "?pageSize=0", "?pageSize=101", "?pageSize=x", "?limit=0", "?limit=101",
            "?pageSize=5&q=a", "?pageSize=5&q=%20a%20", "?pageSize=5&q=", "?pageSize=5&pageSize=6", "?pageSize=5&q=ab&q=cd"})
    void rejectsAmbiguousOrOutOfBoundsDiscoveryForms(String parameters) throws Exception {
        for (var path : List.of(REPOSITORIES, WORKFLOWS)) {
            var response = request(base, path + parameters, identity.session(GITHUB_USER), null, Map.of());
            assertEquals(400, response.statusCode(), path + parameters);
            assertEquals("INVALID_REQUEST", JSON.readTree(response.body()).get("code").asString(), path + parameters);
        }
        var longQuery = request(base, REPOSITORIES + "?pageSize=5&q=" + "a".repeat(101), identity.session(GITHUB_USER), null, Map.of());
        assertEquals(400, longQuery.statusCode());
        assertEquals("INVALID_REQUEST", JSON.readTree(longQuery.body()).get("code").asString());
        assertEquals(0, PROVIDER.reads);
    }

    @Test void legacyLimitFormIsUnchanged() throws Exception {
        var session = identity.session(GITHUB_USER);
        var single = JSON.readTree(request(base, REPOSITORIES + "?limit=5", session, null, Map.of()).body());
        assertEquals(List.of("823001"), ids(List.of(single), "repositories", "repositoryId"));
        assertTrue(single.get("nextPageToken").isNull());
        PROVIDER.repositoryList = List.of(repository("30", "example/c"), repository("10", "example/a"), repository("20", "example/b"));
        var complete = request(base, REPOSITORIES + "?limit=3", session, null, Map.of());
        assertEquals(200, complete.statusCode(), complete.body());
        assertEquals(List.of("30", "10", "20"), ids(List.of(JSON.readTree(complete.body())), "repositories", "repositoryId"));
        assertEquals(503, request(base, REPOSITORIES + "?limit=2", session, null, Map.of()).statusCode());
        var workflows = JSON.readTree(request(base, WORKFLOWS + "?limit=5", session, null, Map.of()).body());
        assertEquals(1, workflows.get("workflows").size());
        assertTrue(workflows.get("nextPageToken").isNull());
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
        for (var path : List.of(REPOSITORIES, "/api/v1/github/repositories/{repositoryId}/workflows")) {
            var parameters = new HashMap<String, JsonNode>();
            for (var parameter : schema.get("paths").get(path).get("get").get("parameters")) { parameters.put(parameter.get("name").asString(), parameter.get("schema")); }
            for (var name : List.of("limit", "pageSize")) {
                assertEquals("integer", parameters.get(name).get("type").asString(), name);
                assertEquals(1, parameters.get(name).get("minimum").asInt(), name);
                assertEquals(100, parameters.get(name).get("maximum").asInt(), name);
            }
            assertEquals(2, parameters.get("q").get("minLength").asInt());
            assertEquals(100, parameters.get("q").get("maxLength").asInt());
            assertEquals(512, parameters.get("pageToken").get("maxLength").asInt());
            assertFalse(parameters.containsKey("cursor"));
        }
        for (var resource : List.of("Repositories", "Workflows")) {
            var token = schema.get("components").get("schemas").get(resource).get("properties").get("nextPageToken");
            assertEquals("[\"string\",\"null\"]", token.get("type").toString(), resource);
        }
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
                "pipesor.api.limits.max-page-size=100", "pipesor.api.limits.max-request-bytes=16384",
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
    /** Follows nextPageToken until the last page, checking that every token is opaque URL-safe text within 512 characters. */
    private static List<JsonNode> pages(String path, String items) throws Exception {
        var pages = new ArrayList<JsonNode>();
        String token = null;
        do {
            var response = request(base, token == null ? path : path + "&pageToken=" + token, identity.session(GITHUB_USER), null, Map.of());
            assertEquals(200, response.statusCode(), response.body());
            var page = JSON.readTree(response.body());
            assertTrue(page.has("nextPageToken") && page.get(items).isArray(), response.body());
            pages.add(page);
            token = page.get("nextPageToken").isNull() ? null : page.get("nextPageToken").asString();
            assertTrue(token == null || token.length() <= 512 && token.matches("[A-Za-z0-9_-]+"), token);
            assertTrue(pages.size() <= 20, "paging terminates");
        } while (token != null);
        return pages;
    }
    /** Item IDs across pages, in order; fails on any duplicate. */
    private static List<String> ids(List<JsonNode> pages, String items, String id) {
        var result = new ArrayList<String>();
        for (var page : pages) { for (var item : page.get(items)) { result.add(item.get(id).asString()); } }
        assertEquals(result.size(), new HashSet<>(result).size(), "no item appears twice");
        return result;
    }
    private static Set<?> fields(JsonNode node) { return new TreeSet<>(JSON.convertValue(node, Map.class).keySet()); }
    private static String query(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"); }
    private static RepositoryFacts repository(String id, String fullName) {
        return new RepositoryFacts(new RepositoryTarget(id, "5001"), "account-7", true, true, true, true, true, fullName, "main");
    }
    /** 150 repositories in reverse order, alternating owner case; "aaa" twice with IDs 10 and 9 to check numeric tie-breaking. */
    private static List<RepositoryFacts> manyRepositories() {
        var repositories = new ArrayList<RepositoryFacts>();
        for (int index = 0; index < 148; index++) {
            repositories.add(repository(Integer.toString(900000 + index), (index % 2 == 0 ? "Example/" : "example/")
                    + "Repo-" + String.format("%03d", 147 - index)));
        }
        repositories.add(repository("10", "example/AAA"));
        repositories.add(repository("9", "Example/aaa"));
        return List.copyOf(repositories);
    }
    private static List<String> expectedRepositoryOrder() {
        var expected = new ArrayList<>(List.of("9", "10"));
        for (int index = 147; index >= 0; index--) { expected.add(Integer.toString(900000 + index)); }
        return expected;
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
        volatile List<RepositoryFacts> repositoryList; volatile List<WorkflowMetadata> workflowList; volatile int installationReads;
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
        @Override public Observation<Identity, List<InstallationRepositories>> installations(Identity request) {
            installationReads++;
            var repositories = repositories(request).value();
            return observed(request, List.of(new InstallationRepositories("5001", repositories.get(0).accountId(), "example", true, repositories)));
        }
        @Override public Observation<Identity, List<RepositoryFacts>> repositories(Identity request) {
            if (repositoryList != null) { return observed(request, repositoryList); }
            return observed(request, extraRepositories ? List.of(facts(new RepositoryTarget("823001", "5001")), facts(new RepositoryTarget("823002", "5001")))
                    : List.of(facts(new RepositoryTarget("823001", "5001"))));
        }
        @Override public Observation<RepositoryRequest, List<WorkflowMetadata>> workflows(RepositoryRequest request) {
            if (workflowList != null) {
                return observed(request, workflowList.stream().map(workflow -> new WorkflowMetadata(request.repository(), workflow.workflowId(),
                        workflow.name(), workflow.file())).toList());
            }
            return observed(request, List.of(workflow(request.repository())));
        }
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
