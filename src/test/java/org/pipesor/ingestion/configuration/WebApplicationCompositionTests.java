package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipesor.ingestion.IngestionServiceApplication;
import org.pipesor.ingestion.auth.application.services.GitHubLogin;
import org.pipesor.ingestion.capture.infrastructure.scheduling.PublicationRunner;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.infrastructure.provider.ConnectedGitHubLink;
import org.pipesor.ingestion.shared.security.PlatformTestIdentity;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The production composition under the local profile against PostgreSQL (pgvector image of the local
 * stack): Flyway at startup, health groups, JWKS with both keys, the route catalog and the login start.
 */
class WebApplicationCompositionTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String AUTHORITY = """
        {"formatVersion":"1","revision":"r1","humanAdmissions":[{"tenantId":"11111111-1111-4111-8111-111111111111","appId":"120",
         "accountId":"240","installationId":"360","repositoryIds":["480"]}],"machineScopes":[
        {"captureDomain":"pilot","tenantId":"11111111-1111-4111-8111-111111111111","appId":"120","accountId":"240",
         "installationId":"360","repositoryId":"480","workflowId":"590","scopeRevision":"r1","allowedActions":["completed"],
         "replayWindow":"PT20M"}]}
        """;
    static PostgreSQLContainer postgres;
    static PlatformTestIdentity identity;

    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
        postgres.start();
        identity = new PlatformTestIdentity();
    }
    @AfterAll static void stop() { identity.close(); postgres.stop(); }

    static List<String> settings(Path directory) throws Exception {
        var app = PlatformTestIdentity.pem(directory.resolve("app.pem"), PlatformTestIdentity.rsa());
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(app)));
        var secret = Files.writeString(directory.resolve("client-secret"), "synthetic-client-secret");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
        var authority = Files.writeString(directory.resolve("authority.json"), AUTHORITY);
        var values = new ArrayList<>(identity.properties(directory));
        values.remove("management.endpoint.health.group.readiness.include=readinessState");
        values.addAll(List.of(
                "--pipesor.session.signing-key-file=" + PlatformTestIdentity.pem(directory.resolve("session.pem"), identity.session),
                "--pipesor.session.dashboard-origin=http://127.0.0.1:5173",
                "--pipesor.github.app-id=120", "--pipesor.github.callback-url=http://127.0.0.1:8081/api/v1/auth/github/callback", "--pipesor.github.client-id=Iv1.synthetic", "--pipesor.github.capture-domain=pilot",
                "--pipesor.github.authority-file=" + authority, "--pipesor.github.private-key-file=" + app,
                "--pipesor.github.private-key-sha256=" + digest, "--pipesor.github.client-secret-file=" + secret,
                "--pipesor.github.custody-directory=" + directory.resolve("custody"),
                "--pipesor.service-bus.connection-string=Endpoint=sb://127.0.0.1;SharedAccessKeyName=synthetic;SharedAccessKey=c3ludGhldGlj;UseDevelopmentEmulator=true",
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(), "--server.port=0", "--server.address=127.0.0.1"));
        return values.stream().map(value -> value.startsWith("--") ? value : "--" + value).toList();
    }

    @Test
    void localProfileComposesEveryRouteWithFlywayHealthAndBothPublicKeys(@TempDir Path directory) throws Exception {
        try (var context = new SpringApplicationBuilder(IngestionServiceApplication.class).run(settings(directory).toArray(String[]::new))) {
            assertInstanceOf(ConnectedGitHubLink.class, context.getBean(CurrentGitHubLink.class));
            assertNotNull(context.getBean(GitHubLogin.class));
            assertNotNull(context.getBean(PublicationRunner.class));
            assertEquals(Integer.valueOf(4), new org.springframework.jdbc.core.JdbcTemplate(context.getBean(javax.sql.DataSource.class))
                    .queryForObject("select count(*) from ingestion.flyway_schema_history where success and version is not null", Integer.class));
            var base = URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort());
            try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
                for (var probe : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
                    var health = get(client, base, probe);
                    assertEquals(200, health.statusCode(), probe);
                    assertEquals("UP", JSON.readTree(health.body()).get("status").asString());
                }
                assertEquals(401, get(client, base, "/actuator/health").statusCode());
                var jwks = JSON.readTree(get(client, base, "/.well-known/jwks.json").body());
                assertEquals(2, jwks.get("keys").size());
                var denied = get(client, base, "/api/v1/github/repositories?limit=1");
                assertEquals(401, denied.statusCode());
                assertTrue(denied.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
                var start = get(client, base, "/api/v1/auth/github/start?return_to=/pipelines");
                assertEquals(302, start.statusCode(), start.body());
                var location = start.headers().firstValue("Location").orElseThrow();
                assertTrue(location.startsWith("https://github.com/login/oauth/authorize?client_id=Iv1.synthetic&redirect_uri="
                        + java.net.URLEncoder.encode("http://127.0.0.1:8081/api/v1/auth/github/callback", java.nio.charset.StandardCharsets.UTF_8)), location);
                assertTrue(location.contains("&code_challenge_method=S256"));
                assertEquals(400, get(client, base, "/api/v1/auth/github/start?return_to=https://evil.example").statusCode());
                var callback = get(client, base, "/api/v1/auth/github/callback?code=x&state=" + "A".repeat(43));
                assertEquals(302, callback.statusCode());
                assertEquals("http://127.0.0.1:5173/auth/complete?error=invalid_state", callback.headers().firstValue("Location").orElseThrow());
                var exchange = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/session")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"handoff\":\"" + "B".repeat(43) + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(400, exchange.statusCode());
                assertEquals(401, client.send(HttpRequest.newBuilder(base.resolve("/api/v1/auth/logout")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                var machine = client.send(HttpRequest.newBuilder(base.resolve(WebConfiguration.MACHINE_SCOPE_PATH)).header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + identity.workload("Pipeline.GitHubFacts.Read"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, machine.statusCode());
            }
        }
    }

    @Test
    void importedAdmissionsLetPipelineDriveTheWorkflowScope(@TempDir Path directory) throws Exception {
        var authorityFile = directory.resolve("authority.json");
        var importArgs = new ArrayList<>(settings(directory));
        importArgs.add("--spring.main.web-application-type=none");
        importArgs.add("--pipesor.admin.import-authority=" + authorityFile);
        var importer = new SpringApplicationBuilder(IngestionServiceApplication.class).run(importArgs.toArray(String[]::new));
        assertFalse(importer.isActive(), "the import command closes its context");
        try (var context = new SpringApplicationBuilder(IngestionServiceApplication.class).run(settings(directory).toArray(String[]::new))) {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from ingestion.repository_admissions where repository_id = '480'", Integer.class));
            var base = URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort());
            var path = "/internal/v1/capture/workflow-scopes/480/591";
            var body = "{\"requestId\":\"" + java.util.UUID.randomUUID() + "\",\"correlationId\":\"c-1\",\"pipelineId\":\""
                    + java.util.UUID.randomUUID() + "\",\"monitoringStatus\":\"ACTIVE\"}";
            try (var client = HttpClient.newHttpClient()) {
                var wrongRole = client.send(put(base, path, identity.workload("Pipeline.GitHubFacts.Read"), body), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, wrongRole.statusCode());
                var synced = client.send(put(base, path, identity.workload("Pipeline.CaptureScope.Write"), body), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, synced.statusCode(), synced.body());
                assertEquals("scope-r1", JSON.readTree(synced.body()).get("scopeRevision").asString());
                var notAdmitted = client.send(put(base, "/internal/v1/capture/workflow-scopes/999/591",
                        identity.workload("Pipeline.CaptureScope.Write"), body), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, notAdmitted.statusCode());
                assertTrue(notAdmitted.body().contains("REPOSITORY_NOT_ADMITTED"));
            }
        }
    }

    private static HttpRequest put(URI base, String path, String token, String body) {
        return HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    @Test
    void localOnlyFilesFailStartupUnderTheAzureProfile(@TempDir Path directory) throws Exception {
        var values = new ArrayList<>(settings(directory));
        values.replaceAll(value -> value.equals("--spring.profiles.active=local") ? "--spring.profiles.active=azure" : value);
        var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(IngestionServiceApplication.class)
                .run(values.toArray(String[]::new)).close());
        Throwable root = failure;
        while (root.getCause() != null) { root = root.getCause(); }
        assertTrue(root.getMessage().contains("local profile") || root.getMessage().contains("HTTPS"), root.getMessage());
    }

    private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
