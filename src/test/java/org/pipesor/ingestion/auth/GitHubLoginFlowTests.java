package org.pipesor.ingestion.auth;

import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.pipesor.ingestion.auth.application.commandhandlers.GitHubLoginHandler;
import org.pipesor.ingestion.auth.application.commands.LoginCommands;
import org.pipesor.ingestion.auth.application.ports.GitHubLoginDirectory;
import org.pipesor.ingestion.auth.application.ports.SessionIssuer;
import org.pipesor.ingestion.auth.application.services.LoginFailure;
import org.pipesor.ingestion.auth.infrastructure.persistence.JdbcLoginAttempts;
import org.pipesor.ingestion.configuration.TestConnectionConfiguration;
import org.pipesor.ingestion.github.application.commandhandlers.RefreshHandler;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.domain.model.valueobjects.*;
import org.pipesor.ingestion.github.infrastructure.custody.FileCredentialCustody;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.ConnectionJpaEntities;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.JpaRefreshSnapshotReader;
import org.pipesor.ingestion.github.infrastructure.provider.ConnectedGitHubLink;
import org.pipesor.ingestion.shared.security.PlatformIds;
import org.pipesor.ingestion.shared.security.PlatformTestIdentity;
import org.pipesor.ingestion.shared.security.SessionTokens;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The login flow of the platform identity contract v1, §5, with real PostgreSQL (ADR-020 connection
 * model, one-time attempts and handoffs), operator-file custody and real session signing; only GitHub
 * is synthetic.
 */
class GitHubLoginFlowTests {
    private static final UUID TENANT = PlatformTestIdentity.TENANT;
    private static final RepositoryTarget REPOSITORY = new RepositoryTarget("480", "360");
    private static PostgreSQLContainer postgres;
    private static ConfigurableApplicationContext context;
    private static final MutableClock CLOCK = new MutableClock();

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ConnectionJpaEntities.class)
    @Import(TestConnectionConfiguration.class)
    static class Persistence {
        @Bean Clock clock() { return CLOCK; }
    }

    static final class MutableClock extends Clock {
        volatile Duration offset = Duration.ZERO;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }

    final class GitHub implements GitHubUserTokens, GitHubLoginDirectory {
        final AtomicInteger exchanges = new AtomicInteger(), refreshes = new AtomicInteger();
        String verifier; boolean administrator = true; boolean reject;
        @Override public Pair exchange(AuthorizationCode request) {
            exchanges.incrementAndGet(); verifier = request.verifier();
            if (reject) throw new Failure(Failure.Reason.REJECTED);
            var now = CLOCK.instant();
            return new Pair("ghu_access" + exchanges.get(), "ghr_refresh" + exchanges.get(), now.plus(Duration.ofHours(8)), now.plus(Duration.ofDays(180)));
        }
        @Override public Pair refresh(String refreshToken) {
            refreshes.incrementAndGet();
            var now = CLOCK.instant();
            return new Pair("ghu_refreshed" + refreshes.get(), "ghr_rotated" + refreshes.get(), now.plus(Duration.ofHours(8)), now.plus(Duration.ofDays(180)));
        }
        @Override public Facts read(String userAccessToken, Instant accessExpiresAt) {
            assertTrue(userAccessToken.startsWith("ghu_"));
            return new Facts("42", "octocat", List.of(new RepositoryFacts(REPOSITORY, "240", true, true, true, true, administrator, "org/repo", "main")));
        }
    }

    private GitHub github;
    private GitHubLoginHandler login;
    private ConnectedGitHubLink links;
    private ConnectionStatusReader statuses;
    private final List<URI> authorizeUrls = new ArrayList<>();

    @BeforeAll static void start() {
        postgres = new PostgreSQLContainer("postgres:17.11");
        postgres.start();
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("ingestion").schemas("ingestion").load().migrate();
        context = new SpringApplicationBuilder(Persistence.class).web(WebApplicationType.NONE)
                .initializers(c -> org.springframework.boot.test.util.TestPropertyValues.of("spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(), "spring.datasource.password=" + postgres.getPassword(),
                        "spring.flyway.enabled=false").applyTo(c)).run();
    }
    @AfterAll static void stop() { if (context != null) context.close(); if (postgres != null) postgres.stop(); }

    @BeforeEach void compose(@TempDir Path directory) throws Exception {
        CLOCK.offset = Duration.ZERO;
        github = new GitHub();
        var custodyDirectory = directory.resolve("custody");
        var custody = new FileCredentialCustody(custodyDirectory, 24000);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(custodyDirectory));
        var connections = context.getBean(GitHubConnections.class);
        statuses = context.getBean(ConnectionStatusReader.class);
        var snapshots = new JpaRefreshSnapshotReader(SharedEntityManagerCreator.createSharedEntityManager(
                context.getBean(jakarta.persistence.EntityManagerFactory.class)), context.getBean(PlatformTransactionManager.class));
        var sessionKey = PlatformTestIdentity.rsa();
        var tokens = new SessionTokens(TENANT, PlatformTestIdentity.key(sessionKey), Duration.ofHours(1), CLOCK);
        SessionIssuer issuer = id -> { var token = tokens.issue(id); return new SessionIssuer.Issued(token.value(), token.expiresAt()); };
        login = new GitHubLoginHandler(TENANT, new JdbcLoginAttempts(new JdbcTemplate(context.getBean(DataSource.class))),
                (state, challenge) -> { var uri = URI.create("https://github.com/login/oauth/authorize?state=" + state + "&code_challenge=" + challenge); authorizeUrls.add(uri); return uri; },
                github, github, (tenant, account, target) -> tenant.equals(TENANT) && account.equals("240") && target.equals(REPOSITORY),
                custody, connections, statuses, issuer, CLOCK);
        links = new ConnectedGitHubLink(statuses, snapshots, custody, new RefreshHandler(connections, snapshots, custody, github, CLOCK), CLOCK);
    }

    @Test void completesTheLoginOnceAndLinksTheVerifiedUserThroughCustody() throws Exception {
        var authorize = login.start(new LoginCommands.Start("/pipelines", "corr-login"));
        var state = query(authorize, "state");
        var handoff = login.callback(new LoginCommands.Callback("synthetic-code", state, null, "corr-login"));
        assertEquals(base64(MessageDigest.getInstance("SHA-256").digest(github.verifier.getBytes(StandardCharsets.US_ASCII))), query(authorize, "code_challenge"));
        assertEquals(LoginFailure.Reason.INVALID_STATE, assertThrows(LoginFailure.class,
                () -> login.callback(new LoginCommands.Callback("synthetic-code", state, null, "corr-login"))).reason());
        assertEquals(1, github.exchanges.get());

        var session = login.exchange(new LoginCommands.Exchange(handoff));
        assertEquals("42", session.githubUserId());
        assertEquals("octocat", session.login());
        assertEquals("/pipelines", session.returnPath());
        var claims = SignedJWT.parse(session.accessToken()).getJWTClaimsSet();
        assertEquals(PlatformIds.human(42).toString(), claims.getStringClaim("oid"));
        assertEquals("42", claims.getStringClaim("github_user_id"));
        assertEquals(PlatformIds.SESSION_ISSUER, claims.getIssuer());
        assertEquals(LoginFailure.Reason.INVALID_HANDOFF, assertThrows(LoginFailure.class,
                () -> login.exchange(new LoginCommands.Exchange(handoff))).reason());

        var context = access();
        var link = links.read(context).value().orElseThrow();
        assertEquals("42", link.githubUserId());
        var credential = links.credential(new ReadOnlyGitHub.Identity(context, link));
        assertEquals("ghu_access1", credential.token());
        assertFalse(credential.toString().contains("ghu_"));
    }

    @Test void refreshesAnExpiringUserTokenBeforeReturningTheLink() {
        complete();
        CLOCK.offset = Duration.ofHours(8).minusMinutes(2);
        var context = access();
        var link = links.read(context).value().orElseThrow();
        assertEquals(1, github.refreshes.get());
        assertEquals("ghu_refreshed1", links.credential(new ReadOnlyGitHub.Identity(context, link)).token());
        assertEquals(link, links.read(context).value().orElseThrow());
        assertEquals(1, github.refreshes.get());
    }

    @Test void relinkInvalidatesTheOldGenerationAndLogoutRemovesTheLink() {
        complete();
        var first = links.read(access()).value().orElseThrow();
        var handoff = callback();
        var second = links.read(access()).value().orElseThrow();
        assertNotEquals(first.generationId(), second.generationId());
        login.logout(new LoginCommands.Logout(new ConnectionIdentity(TENANT, PlatformIds.human(42))));
        assertTrue(links.read(access()).value().isEmpty());
        assertEquals(LoginFailure.Reason.INVALID_HANDOFF, assertThrows(LoginFailure.class,
                () -> login.exchange(new LoginCommands.Exchange(handoff))).reason());
        login.logout(new LoginCommands.Logout(new ConnectionIdentity(TENANT, PlatformIds.human(42))));
    }

    @Test void refusesUnadmittedUsersDeniedConsentExpiredAttemptsAndForgedValues() {
        github.administrator = false;
        assertEquals(LoginFailure.Reason.NOT_ADMITTED, assertThrows(LoginFailure.class, this::callback).reason());
        github.administrator = true;
        var denied = query(login.start(new LoginCommands.Start("/", "corr")), "state");
        assertEquals(LoginFailure.Reason.ACCESS_DENIED, assertThrows(LoginFailure.class,
                () -> login.callback(new LoginCommands.Callback(null, denied, "access_denied", "corr"))).reason());
        github.reject = true;
        assertEquals(LoginFailure.Reason.ACCESS_DENIED, assertThrows(LoginFailure.class, this::callback).reason());
        github.reject = false;
        var late = query(login.start(new LoginCommands.Start("/", "corr")), "state");
        CLOCK.offset = Duration.ofMinutes(11);
        assertEquals(LoginFailure.Reason.INVALID_STATE, assertThrows(LoginFailure.class,
                () -> login.callback(new LoginCommands.Callback("code", late, null, "corr"))).reason());
        CLOCK.offset = Duration.ZERO;
        var handoff = callback();
        CLOCK.offset = Duration.ofSeconds(61);
        assertEquals(LoginFailure.Reason.INVALID_HANDOFF, assertThrows(LoginFailure.class,
                () -> login.exchange(new LoginCommands.Exchange(handoff))).reason());
        assertEquals(LoginFailure.Reason.INVALID_REQUEST, assertThrows(LoginFailure.class,
                () -> login.start(new LoginCommands.Start("//evil.example", "corr"))).reason());
        assertEquals(LoginFailure.Reason.INVALID_STATE, assertThrows(LoginFailure.class,
                () -> login.callback(new LoginCommands.Callback("code", "forged", null, "corr"))).reason());
        assertEquals(LoginFailure.Reason.INVALID_HANDOFF, assertThrows(LoginFailure.class,
                () -> login.exchange(new LoginCommands.Exchange("forged"))).reason());
    }

    private void complete() { login.exchange(new LoginCommands.Exchange(callback())); }

    private String callback() {
        var state = query(login.start(new LoginCommands.Start("/", "corr")), "state");
        return login.callback(new LoginCommands.Callback("synthetic-code", state, null, "corr"));
    }

    private static AccessContext access() {
        var subject = TENANT + ":" + PlatformIds.human(42);
        return new AccessContext(TENANT.toString(), subject, subject, AccessOperation.DISCOVER, "corr");
    }

    private static String query(URI uri, String name) {
        for (var pair : uri.getRawQuery().split("&")) {
            var parts = pair.split("=", 2);
            if (parts[0].equals(name)) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError(name);
    }

    private static String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
}
