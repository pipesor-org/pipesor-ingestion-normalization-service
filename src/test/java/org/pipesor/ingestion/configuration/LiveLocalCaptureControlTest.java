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
        try (var postgres = new PostgreSQLContainer("postgres:17.11")) {
            postgres.start();
            org.flywaydb.core.Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .defaultSchema("ingestion").schemas("ingestion").load().migrate();
            try (var runtime = new SpringApplicationBuilder(IngestionServiceApplication.class)
                    .web(WebApplicationType.NONE).properties(Map.of(
                            "spring.datasource.url", postgres.getJdbcUrl(),
                            "spring.datasource.username", postgres.getUsername(),
                            "spring.datasource.password", postgres.getPassword()))
                    .run("--spring.config.additional-location=file:" + System.getProperty("pipesor.live-capture.properties"))) {
                var authority = runtime.getBean(GitHubFileAuthority.class);
                var analysis = runtime.getBean(RequestGitHubCapture.class);
                var scope = runtime.getBean(CaptureScope.class);
                assertEquals("1446318020", scope.repositoryId());
                assertEquals("412504984", scope.workflowId());
                var receipt = analysis.handle(scope, "43055757606", 1, authority.completedReplayUntil(scope), () -> {});
                assertEquals("PREPARED", receipt.phase());
                assertEquals("PREPARED", analysis.phase(scope, "43055757606", 1).orElseThrow());
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
