package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.interfaces.rest.MachineScopeServlet;
import org.pipesor.ingestion.shared.security.PlatformSecurityConfiguration;
import org.pipesor.ingestion.shared.security.PlatformTestIdentity;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, signature verification and file authority with synthetic workload identities. */
class MachineScopeHttpTests {
    private static final UUID DIRECTORY = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TENANT = PlatformTestIdentity.TENANT;
    private static final String ROLE = "Pipeline.MachineScope.Check";
    private static PlatformTestIdentity identity;
    private static final String CORRELATION = "machine-http-test";
    private static final String AUTHORITY = """
        {"formatVersion":"1","revision":"document-r1","humanAdmissions":[],"machineScopes":[
        {"captureDomain":"pilot","tenantId":"11111111-1111-4111-8111-111111111111","appId":"120",
         "accountId":"240","installationId":"360","repositoryId":"480000000000000000001",
         "workflowId":"590000000000000000002","scopeRevision":"machine-r1","allowedActions":["completed"],
         "replayWindow":"PT20M"}]}
        """;
    static ConfigurableApplicationContext context;
    static URI base;
    static Path directory, file;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
    @Import({PlatformSecurityConfiguration.class, WebConfiguration.class})
    static class Application {
        @Bean
        ServletRegistrationBean<MachineScopeServlet> machineScopeServlet() {
            var settings = new MachineScopeServlet.Settings(TENANT, "120", "pilot", 2048, 2, Duration.ofMillis(300));
            var authority = new GitHubFileAuthority(file, "120", Clock.systemUTC(), 10000, 20);
            var registration = new ServletRegistrationBean<>(new MachineScopeServlet(settings, authority, Clock.systemUTC()), WebConfiguration.MACHINE_SCOPE_PATH);
            registration.setAsyncSupported(true);
            return registration;
        }
    }

    @BeforeAll static void start() throws Exception {
        identity = new PlatformTestIdentity();
        directory = Files.createTempDirectory("machine-scope-http");
        file = directory.resolve("authority.json");
        Files.writeString(file, AUTHORITY);
        startServer();
    }

    private static void startServer() {
        var properties = new ArrayList<>(identity.properties(directory));
        properties.addAll(List.of("server.port=0", "server.address=127.0.0.1", "springdoc.api-docs.enabled=false"));
        context = new SpringApplicationBuilder(Application.class).profiles("local").initializers(c -> org.springframework.boot.test.util.TestPropertyValues.of(properties.toArray(String[]::new)).applyTo(c)).run();
        base = URI.create("http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort());
    }
    @AfterAll static void close() throws Exception { if (context != null) context.close(); identity.close(); try (var files = Files.list(directory)) { for (var f : files.toList()) Files.deleteIfExists(f); } Files.deleteIfExists(directory); }
    @BeforeEach void reset() throws Exception { Files.writeString(file, AUTHORITY); }

    @Test void authorizesExactScopeAndBindsRequestAndCorrelation() throws Exception {
        String body = body();
        var response = post(body, token(Map.of()), CORRELATION);
        assertEquals(200, response.statusCode(), response.body());
        var result = JSON.readTree(response.body());
        assertEquals(JSON.readTree(body).get("requestId").asString(), result.get("requestId").asString());
        assertEquals(CORRELATION, result.get("correlationId").asString());
        assertEquals("AUTHORIZED", result.get("decision").asString());
        assertEquals(Set.of("requestId","correlationId","decision"), result.propertyNames());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void separatesInvalidAuthenticationFromDisallowedCallerAndRole() throws Exception {
        assertEquals(401, post(body(), null, CORRELATION).statusCode());
        for (var changed : List.<Map<String,Object>>of(
                Map.of("aud", "urn:pipesor:api:pipeline"), Map.of("tid", DIRECTORY.toString()), Map.of("idtyp","user"),
                Map.of("scp","capture.read"), Map.of("ver","1.0"), Map.of("exp",Date.from(Instant.now().minusSeconds(1))),
                Map.of("azp",DIRECTORY.toString()), Map.of("oid",DIRECTORY.toString()), Map.of("roles",List.of("wrong")))) {
            assertEquals(401, post(body(),token(changed),CORRELATION).statusCode(), changed.toString());
        }
        // A trusted caller with another catalog role, or a human session, is not the catalog caller of this route.
        assertEquals(403, post(body(), identity.workload("Pipeline.GitHubFacts.Read"), CORRELATION).statusCode());
        assertEquals(403, post(body(), identity.session(42), CORRELATION).statusCode());
    }

    @Test void returnsResourceDenialAndUnavailableWithoutProtectedDetails() throws Exception {
        for (String changed : List.of(body().replace("machine-r1","machine-r0"), body().replace(TENANT.toString(),DIRECTORY.toString()),
                body().replace("\"240\"","\"241\""), body().replace("\"360\"","\"361\""),
                body().replace("480000000000000000001","480000000000000000002"),
                body().replace("590000000000000000002","590000000000000000003"))) {
            var response = post(changed,token(Map.of()),CORRELATION);
            assertEquals(200,response.statusCode(),response.body());
            assertEquals("DENIED",JSON.readTree(response.body()).get("decision").asString());
        }
        Files.writeString(file,"private-invalid-authority");
        var unavailable = post(body(),token(Map.of()),CORRELATION);
        assertEquals(503,unavailable.statusCode(),unavailable.body());
        assertFalse(unavailable.body().contains("private"));
        assertEquals("UNAVAILABLE", JSON.readTree(unavailable.body()).get("code").asString());
    }

    @Test void checksOnlyTheCurrentFileAndDoesNotDetectRevisionReuse() throws Exception {
        var bearer = token(Map.of());
        assertDecision(bearer, "AUTHORIZED");
        Files.writeString(file, AUTHORITY.replace("machine-r1", "machine-r2"));
        assertDecision(bearer, "DENIED");
        Files.writeString(file, AUTHORITY.replace("[\"completed\"]", "[\"in_progress\"]"));
        assertDecision(bearer, "DENIED");
        Files.writeString(file, "{");
        assertEquals(503, post(body(), bearer, CORRELATION).statusCode());
        Files.writeString(file, AUTHORITY.replace("machine-r1", "machine-r2"));
        context.close();
        startServer();
        assertDecision(bearer, "DENIED");
        // Restoring the old label makes its tuple current again, even after restart.
        Files.writeString(file, AUTHORITY.replace("document-r1", "document-r2"));
        assertDecision(bearer, "AUTHORIZED");
    }

    private static void assertDecision(String bearer, String decision) throws Exception {
        var response = post(body(), bearer, CORRELATION);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(decision, JSON.readTree(response.body()).get("decision").asString());
    }

    @Test void rejectsBodyMetadataAndExpiryBeforeScopeRead() throws Exception {
        for (var changed : List.of(body()+" {}", body().replace("\"requestId\":", "\"requestId\":\"private\",\"requestId\":"),
                body().replace("machine-r1",""), body().replace("480000000000000000001","0480"))) {
            var bad = post(changed,token(Map.of()),CORRELATION);
            assertEquals(400,bad.statusCode(),bad.body());
            assertFalse(bad.body().contains("private"));
        }
        assertEquals(400,post(body(),token(Map.of()),"other").statusCode());
        assertEquals(413,post("x".repeat(2049),token(Map.of()),CORRELATION).statusCode());
        assertEquals(503,post(body(Instant.now().minusSeconds(1)),token(Map.of()),CORRELATION).statusCode());
    }

    private static String body() throws Exception { return body(Instant.now().plusSeconds(5)); }
    private static String body(Instant deadline) throws Exception {
        return JSON.writeValueAsString(Map.of("requestId",UUID.randomUUID().toString(),"correlationId",CORRELATION,
                "deadlineAt",deadline.toString(),"tenantId",TENANT.toString(),"githubAccountId","240",
                "githubInstallationId","360","githubRepositoryId","480000000000000000001",
                "githubWorkflowId","590000000000000000002","scopeRevision","machine-r1"));
    }
    private static HttpResponse<String> post(String body,String bearer,String correlation) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(WebConfiguration.MACHINE_SCOPE_PATH)).timeout(Duration.ofSeconds(3))
                .header("Content-Type","application/json").header("X-Correlation-Id",correlation)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization","Bearer "+bearer);
        try(var client = HttpClient.newHttpClient()) { return client.send(request.build(),HttpResponse.BodyHandlers.ofString()); }
    }
    private static String token(Map<String,Object> changes) {
        var claims = new HashMap<String,Object>(changes);
        var expiry = claims.remove("exp");
        return identity.workload(ROLE, expiry == null ? Instant.now().plusSeconds(60) : ((Date) expiry).toInstant(), claims, identity.pipeline);
    }
}
