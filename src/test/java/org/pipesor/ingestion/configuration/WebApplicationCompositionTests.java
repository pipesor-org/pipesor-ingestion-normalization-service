package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.IngestionServiceApplication;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.ports.BrokerPublisher;
import org.pipesor.ingestion.capture.application.ports.CaptureLedger;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Full production composition with real PostgreSQL; no identity or cloud provider is contacted. */
class WebApplicationCompositionTests {
    @Test
    void webStartsWithOptionalIntegrationsDisabledAndNeverComposesSuccessfulFallbacks() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17.11")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .defaultSchema("ingestion").schemas("ingestion").load().migrate();
            try (var context = new SpringApplicationBuilder(IngestionServiceApplication.class).run(
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(),
                    "--server.port=0", "--server.address=127.0.0.1",
                    "--ingestion.capture.enabled=false", "--ingestion.protection.enabled=false", "--ingestion.messaging.enabled=false",
                    "--ingestion.api.security.issuer=https://login.example.invalid/tenant/v2.0",
                    "--ingestion.api.security.jwk-set-uri=https://login.example.invalid/keys",
                    "--ingestion.api.security.tenant=11111111-1111-4111-8111-111111111111",
                    "--ingestion.api.security.audience=api://composition-test", "--ingestion.api.security.token-version=2.0",
                    "--ingestion.api.security.self-scope=github.self", "--ingestion.api.security.internal-scope=github.internal",
                    "--ingestion.api.security.self-callers=22222222-2222-4222-8222-222222222222",
                    "--ingestion.api.security.pipeline-callers=33333333-3333-4333-8333-333333333333",
                    "--ingestion.api.security.clock-skew=0s",
                    "--ingestion.api.limits.request-timeout=5s", "--ingestion.api.limits.max-batch-size=10",
                    "--ingestion.api.limits.max-page-size=10", "--ingestion.api.limits.max-request-bytes=8192",
                    "--ingestion.api.limits.max-response-bytes=8192", "--ingestion.api.limits.max-context-length=128")) {
                assertNotNull(context.getBean(CaptureLedger.class));
                for (var absent : new Class<?>[] { CaptureHandler.class, BrokerPublisher.class, GitHubQueries.class,
                        NormalizeObservationHandler.class, EvidenceProtection.class, GitHubUserTokens.class, GitHubCredentialCustody.class }) {
                    assertTrue(context.getBeansOfType(absent).isEmpty(), absent.getName());
                }
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/github/repositories?limit=1")).GET().build();
                try (var client = HttpClient.newHttpClient()) {
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(401, response.statusCode());
                    assertTrue(response.body().contains("UNAUTHENTICATED"));
                }
            }
        }
    }
}
