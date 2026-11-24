package org.pipesor.ingestion.configuration;

import com.azure.identity.DefaultAzureCredentialBuilder;
import java.time.Clock;
import java.util.List;
import org.pipesor.ingestion.auth.application.commandhandlers.GitHubLoginHandler;
import org.pipesor.ingestion.auth.application.ports.ConsentRecords;
import org.pipesor.ingestion.auth.application.ports.GitHubAuthorization;
import org.pipesor.ingestion.auth.application.ports.LoginAttempts;
import org.pipesor.ingestion.auth.application.ports.LoginTransactions;
import org.pipesor.ingestion.auth.application.ports.SessionIssuer;
import org.pipesor.ingestion.auth.application.queryhandlers.LoginConsentQueryHandler;
import org.pipesor.ingestion.auth.application.services.GitHubLogin;
import org.pipesor.ingestion.auth.application.services.LoginConsentQueries;
import org.pipesor.ingestion.auth.infrastructure.github.GitHubAuthorizeUrls;
import org.pipesor.ingestion.auth.infrastructure.persistence.JdbcConsentRecords;
import org.pipesor.ingestion.auth.infrastructure.persistence.JdbcLoginAttempts;
import org.pipesor.ingestion.auth.infrastructure.persistence.SpringLoginTransactions;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.infrastructure.oauth.GitHubAppClientId;
import org.pipesor.ingestion.github.infrastructure.provider.GitHubReadProvider;
import org.pipesor.ingestion.shared.security.KeyVaultSigningKey;
import org.pipesor.ingestion.shared.security.PemSigningKey;
import org.pipesor.ingestion.shared.security.PlatformSecurityProperties;
import org.pipesor.ingestion.shared.security.SessionTokens;
import org.pipesor.ingestion.shared.security.SigningKey;
import org.pipesor.ingestion.shared.security.TransportPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Session issuance and the GitHub login flow (platform identity contract v1, §3 and §5). Ingestion
 * holds the session key ({@code pipesor-session-signing}) in addition to its workload key; the JWKS
 * endpoint publishes both.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties.class)
public class SessionConfiguration {
    @Bean
    SigningKey sessionSigningKey(SessionProperties session, TransportPolicy transport) {
        if (session.signingKeyFile() != null) {
            GitHubConfiguration.local(transport, "pipesor.session.signing-key-file");
            return new PemSigningKey(session.signingKeyFile());
        }
        return new KeyVaultSigningKey(session.signingKeyId(), new DefaultAzureCredentialBuilder().build());
    }

    @Bean
    SessionTokens sessionTokens(PlatformSecurityProperties platform, SessionProperties session,
            @Qualifier("sessionSigningKey") SigningKey key) {
        return new SessionTokens(platform.tenantId(), key, session.lifetime(), Clock.systemUTC());
    }

    @Bean
    LoginAttempts loginAttempts(JdbcTemplate jdbc) { return new JdbcLoginAttempts(jdbc); }

    @Bean
    ConsentRecords consentRecords(JdbcTemplate jdbc) { return new JdbcConsentRecords(jdbc); }

    @Bean
    LoginTransactions loginTransactions(PlatformTransactionManager transactions) { return new SpringLoginTransactions(transactions); }

    @Bean
    LoginConsentQueries loginConsentQueries(ConsentRecords records) { return new LoginConsentQueryHandler(records); }

    @Bean
    GitHubAuthorization githubAuthorization(GitHubProperties github, TransportPolicy transport, GitHubAppClientId clientId) {
        return new GitHubAuthorizeUrls(transport.require(github.webBaseUrl()), transport.require(github.callbackUrl()), clientId);
    }

    @Bean
    GitHubLogin githubLogin(PlatformSecurityProperties platform, LoginAttempts attempts, GitHubAuthorization authorization,
            GitHubUserTokens tokens, GitHubReadProvider directory,
            GitHubCredentialCustody custody, GitHubConnections connections, ConnectionStatusReader statuses,
            SessionTokens sessions, ConsentRecords consents, LoginTransactions transactions, Clock clock) {
        SessionIssuer issuer = githubUserId -> {
            var token = sessions.issue(githubUserId);
            return new SessionIssuer.Issued(token.value(), token.expiresAt());
        };
        return new GitHubLoginHandler(platform.tenantId(), attempts, authorization, tokens, directory, custody,
                connections, statuses, issuer, consents, transactions, clock);
    }

    /** CORS for the configured dashboard origin only, no credentials mode (MVP HTTP API conventions). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SessionProperties session) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(session.dashboardOrigin().toString()));
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID", "If-Match"));
        cors.setExposedHeaders(List.of("X-Correlation-ID", "ETag"));
        cors.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
