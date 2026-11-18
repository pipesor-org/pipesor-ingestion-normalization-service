package org.pipesor.ingestion.configuration;

import java.time.Clock;
import java.util.List;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.interfaces.rest.MachineScopeServlet;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "ingestion.machine-scope.enabled", havingValue = "true")
@EnableConfigurationProperties(MachineScopeProperties.class)
public class MachineScopeConfiguration {
    public static final String PATH = "/internal/v1/capture/machine-scope-checks";

    public record MachineDecoder(JwtDecoder decoder) {}

    @Bean
    @ConditionalOnMissingBean(MachineDecoder.class)
    MachineDecoder machineScopeJwtDecoder(MachineScopeProperties properties) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString()).build();
        decoder.setJwtValidator(validator(properties, Clock.systemUTC()));
        return new MachineDecoder(decoder);
    }

    public static OAuth2TokenValidator<Jwt> validator(MachineScopeProperties properties, Clock clock) {
        var times = new JwtTimestampValidator(properties.clockSkew());
        times.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(times, new JwtIssuerValidator(properties.issuer().toString()), jwt -> {
            boolean valid;
            try {
                var issued = jwt.getIssuedAt();
                valid = "2.0".equals(jwt.getClaimAsString("ver")) && "app".equals(jwt.getClaimAsString("idtyp"))
                        && jwt.getClaim("scp") == null && jwt.getExpiresAt() != null && issued != null
                        && !issued.isAfter(clock.instant().plus(properties.clockSkew()))
                        && jwt.getAudience().contains(properties.audience())
                        && properties.directoryTenant().toString().equals(jwt.getClaimAsString("tid"));
            } catch (RuntimeException malformed) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid workload identity", null));
        });
    }

    @Bean
    @Order(-1)
    SecurityFilterChain machineScopeSecurity(HttpSecurity http, MachineScopeProperties properties,
            MachineDecoder machine) throws Exception {
        return http.securityMatcher(PATH)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable()).csrf(c -> c.disable()).logout(c -> c.disable())
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.POST, PATH).access((auth, context) -> {
                    boolean permitted = false;
                    if (auth.get() instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken token) {
                        var jwt = token.getToken();
                        try {
                            var roles = jwt.getClaimAsStringList("roles");
                            permitted = properties.callerClient().toString().equals(jwt.getClaimAsString("azp"))
                                    && properties.callerPrincipal().toString().equals(jwt.getClaimAsString("oid"))
                                    && roles != null && roles.contains(properties.role());
                        } catch (RuntimeException malformed) { permitted = false; }
                    }
                    return new org.springframework.security.authorization.AuthorizationDecision(permitted);
                }).anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((q, r, e) -> MachineScopeServlet.problem(r, 401))
                        .accessDeniedHandler((q, r, e) -> MachineScopeServlet.problem(r, 403)))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(machine.decoder()))
                        .authenticationEntryPoint((q, r, e) -> MachineScopeServlet.problem(r, 401))
                        .accessDeniedHandler((q, r, e) -> MachineScopeServlet.problem(r, 403))).build();
    }

    @Bean
    ServletRegistrationBean<MachineScopeServlet> machineScopeServlet(MachineScopeProperties properties) {
        var authority = new GitHubFileAuthority(properties.authorityFile(), properties.appId(), Clock.systemUTC(),
                properties.maxFileBytes(), properties.maxEntries());
        var registration = new ServletRegistrationBean<>(new MachineScopeServlet(properties, authority, Clock.systemUTC()), PATH);
        registration.setAsyncSupported(true);
        return registration;
    }
}
