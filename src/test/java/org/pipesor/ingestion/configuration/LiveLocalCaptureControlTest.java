package org.pipesor.ingestion.configuration;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pipesor.ingestion.IngestionServiceApplication;
import org.pipesor.ingestion.capture.application.commandhandlers.RequestGitHubCapture;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in operator execution against GitHub with the operational beans and fresh PostgreSQL. */
@EnabledIfSystemProperty(named = "pipesor.live-capture.properties", matches = "/.+")
class LiveLocalCaptureControlTest {
    @Test
    void acquiresAndFreezesVerifiedSource() {
        var propertiesPath = System.getProperty("pipesor.live-capture.properties");
        var settings = new java.util.Properties();
        try (var file = java.nio.file.Files.newInputStream(java.nio.file.Path.of(propertiesPath))) {
            settings.load(file);
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("Capture settings unavailable");
        }
        boolean persistent = settings.containsKey("spring.datasource.url");
        try (var postgres = persistent ? null : new PostgreSQLContainer("postgres:17.11")) {
            if (postgres != null) postgres.start();
            var url = persistent ? settings.getProperty("spring.datasource.url") : postgres.getJdbcUrl();
            var username = persistent ? settings.getProperty("spring.datasource.username") : postgres.getUsername();
            var password = persistent ? settings.getProperty("spring.datasource.password") : postgres.getPassword();
            org.flywaydb.core.Flyway.configure().dataSource(url, username, password)
                    .defaultSchema("ingestion").schemas("ingestion").load().migrate();
            try (var runtime = new SpringApplicationBuilder(IngestionServiceApplication.class)
                    .web(WebApplicationType.NONE).properties(Map.of(
                            "spring.datasource.url", url, "spring.datasource.username", username,
                            "spring.datasource.password", password))
                    .run("--spring.config.additional-location=file:" + System.getProperty("pipesor.live-capture.properties"))) {
                var authority = runtime.getBean(GitHubFileAuthority.class);
                var analysis = runtime.getBean(RequestGitHubCapture.class);
                var scope = runtime.getBean(CaptureScope.class);
                assertEquals("1446318020", scope.repositoryId());
                assertEquals("412504984", scope.workflowId());
                var receipt = analysis.handle(scope, "43055757606", 1, authority.completedReplayUntil(scope), () -> {});
                var spool = settings.getProperty("ingestion.local-capture.spool-directory");
                assertEquals(spool == null ? "PREPARED" : "PUBLISHED", receipt.phase());
                assertEquals(receipt.phase(), analysis.phase(scope, "43055757606", 1).orElseThrow());
                if (spool != null) {
                    assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(spool, receipt.evidenceMessageId() + ".json")));
                    assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(spool, receipt.snapshotMessageId() + ".json")));
                }
                var sql = new JdbcTemplate(runtime.getBean(javax.sql.DataSource.class));
                var bytes = sql.queryForObject("SELECT payload FROM ingestion.publication_outputs WHERE id = ?",
                        byte[].class, receipt.evidenceMessageId());
                var evidence = JsonMapper.builder().build().readTree(bytes);
                assertEquals("2.1", evidence.get("schema_version").asString());
                var locator = evidence.at("/evidence/source_locator").asString();
                assertEquals("v2:job:130644639649:member:0_verify.txt:lines:31-31:sha256:"
                        + "28a1babbb7adec8029801c6eaefd85584e1f485b7f831e8926192dfd2bdc0613", locator);
                assertEquals(2, sql.queryForObject(
                        "SELECT count(*) FROM ingestion.publication_outputs WHERE observation_id = ?", Integer.class,
                        receipt.observationId()));
                var replay = analysis.handle(scope, "43055757606", 1, authority.completedReplayUntil(scope), () -> {});
                assertEquals(receipt.id(), replay.id());
                assertEquals(receipt.evidenceMessageId(), replay.evidenceMessageId());
                System.out.printf("Live GitHub capture frozen in PostgreSQL run=%s attempt=%d locator=%s%n",
                        receipt.runId(), receipt.runAttempt(), locator);
            }
        }
    }
}
