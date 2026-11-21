package org.pipesor.ingestion.configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.capture.application.commandhandlers.CaptureHandler;
import org.pipesor.ingestion.capture.application.commandhandlers.PrepareGitHubCapture;
import org.pipesor.ingestion.capture.application.commandhandlers.RequestGitHubCapture;
import org.pipesor.ingestion.capture.application.ports.CurrentMachineScope;
import org.pipesor.ingestion.capture.application.ports.BrokerPublisher;
import org.pipesor.ingestion.capture.infrastructure.messaging.LocalPublicationSpool;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.infrastructure.credentials.LocalAppSigningKey;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubAppJwtSigner;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubMachineTransport;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.HookContext;
import org.pipesor.ingestion.github.infrastructure.provider.GitHubReadProvider;
import org.pipesor.ingestion.github.infrastructure.provider.LocalGitHubUserLink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/** Explicit loopback-only composition using the same capture ports as the hosted service. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ingestion.local-capture.enabled", havingValue = "true")
public class LocalGitHubCaptureConfiguration {
    private static final String PREFIX = "ingestion.local-capture.";

    @Bean CaptureScope localCaptureScope(Environment env) {
        if (!"127.0.0.1".equals(env.getRequiredProperty("server.address"))
                || env.getProperty("ingestion.github-machine.enabled", Boolean.class, false)
                || env.getProperty("ingestion.github-webhook.enabled", Boolean.class, false)) {
            throw new IllegalArgumentException("Local capture requires exclusive IPv4 loopback composition");
        }
        return new CaptureScope(required(env, "domain"), UUID.fromString(required(env, "tenant")),
                required(env, "app"), required(env, "account"), required(env, "installation"),
                required(env, "repository"), required(env, "workflow"), required(env, "revision"));
    }

    @Bean GitHubFileAuthority localFileAuthority(Environment env, CaptureScope scope, Clock clock) {
        var authority = new GitHubFileAuthority(Path.of(required(env, "authority-file")), scope.appId(), clock, 65536, 64);
        authority.validate();
        return authority;
    }

    @Bean @Primary CurrentMachineScope localCompletedAuthority(GitHubFileAuthority file, CaptureScope scope) {
        var route = new HookContext(scope.captureDomain(), scope.tenantId(), scope.appId());
        return candidate -> scope.equals(candidate)
                ? file.verifyProtectedEvidenceScope(route, candidate.context(), candidate.pipeline())
                : CurrentMachineScope.Decision.CHANGED_OR_LOST;
    }

    @Bean GitHubMachineTransport localMachineTransport(Environment env, CaptureScope scope, Clock clock,
            @Qualifier("localCompletedAuthority") CurrentMachineScope authority,
            GitHubMachineTransport.ArchiveLimits limits) {
        var keyFile = Path.of(required(env, "signing-key-file"));
        var keyDigest = required(env, "signing-key-sha256");
        var signer = new GitHubAppJwtSigner(scope.appId(), () -> LocalAppSigningKey.read(keyFile, keyDigest), clock,
                Duration.ofMinutes(4), Duration.ofSeconds(30));
        return new GitHubMachineTransport(authority, signer, scope.appId(), required(env, "api-version"),
                Duration.ofSeconds(35), 100, 2, 100, 4_000_000, 1_000_000, limits,
                Set.of("results-receiver.actions.githubusercontent.com"), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "ingestion.local-capture.user-link-file")
    LocalGitHubUserLink localGitHubUserLink(Environment env, Clock clock, CaptureScope scope) {
        return new LocalGitHubUserLink(Path.of(required(env, "user-link-file")), clock, scope.appId());
    }

    @Bean
    @ConditionalOnProperty(name = "ingestion.local-capture.user-link-file")
    GitHubReadProvider localUserReadProvider(Environment env, Clock clock, CaptureScope scope, LocalGitHubUserLink link) {
        return new GitHubReadProvider(link::credential, scope.appId(), required(env, "api-version"),
                Duration.ofSeconds(12), 100, 8, 500, 4_000_000, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "ingestion.local-capture.spool-directory")
    BrokerPublisher localPublicationSpool(Environment env) {
        return new LocalPublicationSpool(Path.of(required(env, "spool-directory")));
    }

    @Bean RequestGitHubCapture requestGitHubCapture(CaptureHandler capture, GitHubMachineTransport github,
            PrepareGitHubCapture preparation, Clock clock, Environment env) {
        return new RequestGitHubCapture(capture, github, preparation, clock, env.containsProperty(PREFIX + "spool-directory"));
    }

    private static String required(Environment env, String name) {
        return env.getRequiredProperty(PREFIX + name);
    }
}
