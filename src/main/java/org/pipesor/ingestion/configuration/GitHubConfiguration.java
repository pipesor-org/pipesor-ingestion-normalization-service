package org.pipesor.ingestion.configuration;

import com.azure.identity.DefaultAzureCredentialBuilder;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.PrivateKey;
import java.time.Clock;
import java.util.function.Supplier;
import org.pipesor.ingestion.capture.infrastructure.configuration.CaptureAuthority;
import org.pipesor.ingestion.capture.infrastructure.persistence.jdbc.JdbcCaptureAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.pipesor.ingestion.capture.infrastructure.credentials.KeyVaultAppMaterial;
import org.pipesor.ingestion.capture.infrastructure.credentials.LocalAppSigningKey;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubAppJwtSigner;
import org.pipesor.ingestion.github.application.commandhandlers.GitHubConnectionHandler;
import org.pipesor.ingestion.github.application.commandhandlers.RefreshHandler;
import org.pipesor.ingestion.github.application.ports.*;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubConnectionQueryHandler;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubQueryHandler;
import org.pipesor.ingestion.github.application.services.GitHubConnectionQueries;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.infrastructure.custody.FileCredentialCustody;
import org.pipesor.ingestion.github.infrastructure.custody.KeyVaultCredentialCustody;
import org.pipesor.ingestion.github.infrastructure.oauth.GitHubAppClientId;
import org.pipesor.ingestion.github.infrastructure.oauth.GitHubOAuthUserTokens;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.JpaConnectionRepository;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.JpaConnectionStatusReader;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.JpaRefreshSnapshotReader;
import org.pipesor.ingestion.github.infrastructure.persistence.jpa.TransactionalGitHubConnections;
import org.pipesor.ingestion.github.infrastructure.provider.ConnectedGitHubLink;
import org.pipesor.ingestion.github.infrastructure.provider.GitHubReadProvider;
import org.pipesor.ingestion.shared.security.TransportPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * GitHub App material, the operator authority file, the ADR-020 connection model, user-token custody
 * and the user read provider. File material and file custody exist only under the {@code local} profile;
 * {@code azure} uses Key Vault through {@code DefaultAzureCredential}. Every bean is required: a missing
 * setting fails startup instead of producing a disabled route.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GitHubProperties.class, ApiProperties.class})
public class GitHubConfiguration {
    /** The App's private credentials, from operator files (local) or Key Vault (azure). */
    public record AppMaterial(Supplier<PrivateKey> privateKey, Supplier<String> clientSecret, Supplier<byte[]> webhookSecret) {
        @Override public String toString() { return "AppMaterial[private]"; }
    }

    @Bean
    Clock githubClock() { return Clock.systemUTC(); }

    @Bean
    AppMaterial githubAppMaterial(GitHubProperties github, TransportPolicy transport) {
        var limits = github.limits();
        if (github.fileMaterial()) {
            local(transport, "pipesor.github.private-key-file");
            Supplier<PrivateKey> key = () -> LocalAppSigningKey.read(github.privateKeyFile(), github.privateKeySha256());
            key.get();
            Supplier<String> secret = () -> new String(ownerOnly(github.clientSecretFile(), limits.maxSecretBytes()), StandardCharsets.UTF_8).strip();
            secret.get();
            Supplier<byte[]> webhook = () -> ownerOnly(github.webhookSecretFile(), limits.maxSecretBytes());
            return new AppMaterial(key, secret, webhook);
        }
        var client = KeyVaultCredentialCustody.secretClient(github.keyVaultUrl(), limits.vaultTimeout(),
                () -> new DefaultAzureCredentialBuilder().build());
        var material = new KeyVaultAppMaterial(client, github.keyVaultUrl(), github.appId(),
                new KeyVaultAppMaterial.Reference(github.privateKeySecretName(), github.privateKeySecretVersion()),
                new KeyVaultAppMaterial.Reference(github.clientSecretName(), github.clientSecretVersion()),
                github.webhookEnabled() ? new KeyVaultAppMaterial.Reference(github.webhookSecretName(), github.webhookSecretVersion()) : null,
                limits.maxSecretBytes());
        return new AppMaterial(material::signingKey, material::clientSecret, material::webhookSecret);
    }

    @Bean
    GitHubAppJwtSigner githubAppJwtSigner(GitHubProperties github, AppMaterial material, Clock clock) {
        return new GitHubAppJwtSigner(github.appId(), material.privateKey(), clock, github.limits().appJwtTtl(),
                github.limits().appJwtIssuedAtSkew());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(AuthorityImportRunner.OPTION)
    AuthorityImportRunner authorityImportRunner(GitHubProperties github, JdbcCaptureAuthority authority,
            PlatformTransactionManager transactions, org.springframework.context.ConfigurableApplicationContext context, Clock clock) {
        return new AuthorityImportRunner(github, authority, transactions, context, clock);
    }

    /** ADR-023: the runtime authority reads owned tables only; the authority file is used by the import command. */
    @Bean
    JdbcCaptureAuthority captureAuthority(GitHubProperties github, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            Clock clock) {
        return new JdbcCaptureAuthority(jdbc, new TransactionTemplate(transactions), github.appId(), github.captureDomain(),
                github.limits().scopeReplayWindow(), clock);
    }

    @Bean
    GitHubCredentialCustody githubCredentialCustody(GitHubProperties github, TransportPolicy transport) {
        if (github.custodyDirectory() != null) {
            local(transport, "pipesor.github.custody-directory");
            return new FileCredentialCustody(github.custodyDirectory(), github.limits().maxSecretBytes());
        }
        var client = KeyVaultCredentialCustody.secretClient(github.custodyVaultUrl(), github.limits().vaultTimeout(),
                () -> new DefaultAzureCredentialBuilder().build());
        return new KeyVaultCredentialCustody(client, github.custodyVaultUrl(), github.limits().maxSecretBytes());
    }

    @Bean
    GitHubAppClientId githubAppClientId(GitHubProperties github, TransportPolicy transport, GitHubAppJwtSigner signer) {
        return new GitHubAppClientId(github.clientId(), http(github.limits().userTimeout()), transport.require(github.apiBaseUrl()),
                signer, github.appId(), github.apiVersion(), github.limits().userTimeout());
    }

    @Bean
    GitHubUserTokens githubUserTokens(GitHubProperties github, TransportPolicy transport, GitHubAppClientId clientId,
            AppMaterial material, Clock clock) {
        var callback = transport.require(github.callbackUrl());
        return new GitHubOAuthUserTokens(http(github.limits().oauthTimeout()),
                transport.resolve(github.webBaseUrl(), "/login/oauth/access_token"),
                () -> new GitHubOAuthUserTokens.ClientCredentials(clientId.get(), material.clientSecret().get()),
                callback, github.limits().oauthTimeout(), github.limits().oauthMaxResponseBytes(), clock, transport);
    }

    @Bean
    ConnectionRepository connectionRepository(EntityManagerFactory factory) {
        return new JpaConnectionRepository(SharedEntityManagerCreator.createSharedEntityManager(factory));
    }

    @Bean
    GitHubConnections githubConnections(ConnectionRepository repository, Clock clock, PlatformTransactionManager manager) {
        return new TransactionalGitHubConnections(new GitHubConnectionHandler(repository, clock), manager);
    }

    @Bean
    ConnectionStatusReader connectionStatusReader(EntityManagerFactory factory, PlatformTransactionManager manager) {
        return new JpaConnectionStatusReader(SharedEntityManagerCreator.createSharedEntityManager(factory), manager);
    }

    @Bean
    RefreshSnapshotReader refreshSnapshotReader(EntityManagerFactory factory, PlatformTransactionManager manager) {
        return new JpaRefreshSnapshotReader(SharedEntityManagerCreator.createSharedEntityManager(factory), manager);
    }

    @Bean
    GitHubConnectionQueries githubConnectionQueries(ConnectionStatusReader reader) {
        return new GitHubConnectionQueryHandler(reader);
    }

    @Bean
    RefreshHandler githubRefreshHandler(GitHubConnections connections, RefreshSnapshotReader snapshots,
            GitHubCredentialCustody custody, GitHubUserTokens tokens, Clock clock) {
        return new RefreshHandler(connections, snapshots, custody, tokens, clock);
    }

    @Bean
    ConnectedGitHubLink connectedGitHubLink(ConnectionStatusReader statuses, RefreshSnapshotReader snapshots,
            GitHubCredentialCustody custody, RefreshHandler refresh, Clock clock) {
        return new ConnectedGitHubLink(statuses, snapshots, custody, refresh, clock);
    }

    @Bean
    GitHubReadProvider githubReadProvider(GitHubProperties github, TransportPolicy transport, ConnectedGitHubLink link, Clock clock) {
        var limits = github.limits();
        return new GitHubReadProvider(http(limits.userTimeout()), transport.require(github.apiBaseUrl()), link::credential,
                github.appId(), github.apiVersion(), limits.userTimeout(), limits.userPageSize(), limits.userMaxPages(),
                limits.userMaxItems(), limits.userMaxResponseBytes(), clock);
    }

    @Bean
    GitHubQueries githubQueries(ConnectedGitHubLink link, CaptureAuthority authority, GitHubReadProvider provider, Clock clock) {
        return new GitHubQueryHandler(link, authority, provider, clock);
    }

    /** Vendor-HMAC ingress for the admitted machine scopes of the configured capture domain. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty(name = "pipesor.github.webhook-enabled")
    org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook githubWorkflowWebhook(GitHubProperties github,CaptureAuthority authority,
            AppMaterial material,org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler handler,
            org.pipesor.ingestion.shared.security.PlatformSecurityProperties platform) {
        var hook=new org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.HookContext(github.captureDomain(),platform.tenantId(),github.appId());
        return new org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook(hook,material.webhookSecret()::get,authority.webhook(hook),java.util.Set.of("completed"),github.limits().webhookMaxBodyBytes(),handler);
    }

    static HttpClient http(java.time.Duration timeout) {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    static void local(TransportPolicy transport, String setting) {
        if (!transport.loopbackHttpAllowed()) throw new IllegalStateException(setting + " requires the local profile");
    }

    private static byte[] ownerOnly(Path file, int maxBytes) {
        try {
            if (file == null || !file.isAbsolute() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------"))
                    && !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("r--------"))) {
                throw new IllegalArgumentException();
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length == 0 || bytes.length > maxBytes) throw new IllegalArgumentException();
                return bytes;
            }
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new IllegalStateException("An owner-only (0600) secret file is required");
        }
    }
}
