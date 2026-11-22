package org.pipesor.ingestion.configuration;

import java.util.List;
import org.pipesor.ingestion.shared.interfaces.rest.ApiBoundaryFilter;
import org.pipesor.ingestion.shared.security.PlatformAuthorities;
import org.pipesor.ingestion.shared.security.PlatformSecurityConfiguration.PlatformRoutes;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/** Ingestion's route catalog (platform identity contract v1, §2; MVP HTTP API v1) and HTTP boundaries. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiProperties.class)
public class WebConfiguration {
    public static final String MACHINE_SCOPE_PATH = "/internal/v1/capture/machine-scope-checks";
    public static final String GITHUB_SELF = "GitHub.Self";

    @Bean
    PlatformRoutes ingestionRoutes() {
        return new PlatformRoutes() {
            @Override public void configure(org.springframework.security.config.annotation.web.configurers
                    .AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry routes) {
                routes.requestMatchers(HttpMethod.GET, "/api/v1/auth/github/start", "/api/v1/auth/github/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/session").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").access(PlatformAuthorities.session(GITHUB_SELF))
                        .requestMatchers("/api/v1/github/**").access(PlatformAuthorities.session(GITHUB_SELF))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/github/**")
                        .access(PlatformAuthorities.workload("pipeline", "Pipeline.GitHubFacts.Read"))
                        .requestMatchers(HttpMethod.POST, MACHINE_SCOPE_PATH)
                        .access(PlatformAuthorities.workload("pipeline", "Pipeline.MachineScope.Check"))
                        .requestMatchers(HttpMethod.PUT, "/internal/v1/capture/workflow-scopes/*/*")
                        .access(PlatformAuthorities.workload("pipeline", "Pipeline.CaptureScope.Write"))
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/error").permitAll();
            }

            @Override public List<String> subjectTokenRoutes() { return List.of("/internal/v1/github/**"); }
        };
    }

    /** Correlation id, body bounds and ambient-identity rejection run before authentication. */
    @Bean
    FilterRegistrationBean<ApiBoundaryFilter> apiBoundaryFilter(ApiProperties api) {
        var registration = new FilterRegistrationBean<>(new ApiBoundaryFilter(api.limits()));
        registration.addUrlPatterns("/api/*", "/internal/v1/github/*");
        registration.setOrder(-101);
        return registration;
    }
}
