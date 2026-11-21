package org.pipesor.ingestion.configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.shared.interfaces.rest.ApiExceptionHandler;
import org.pipesor.ingestion.shared.interfaces.rest.ApiBoundaryFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ApiProperties.class)
public class ApiSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiProperties properties) throws Exception {
        var security = properties.security();
        return http
                .securityMatcher("/api/v1/github/**", "/internal/v1/github/**", "/v3/api-docs/**")
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/github/**").access((auth, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        caller(auth.get(), security.selfCallers(), security.selfScope(), security.tokenVersion())))
                        .requestMatchers("/internal/v1/github/**", "/v3/api-docs/**").access((auth, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        caller(auth.get(), security.pipelineCallers(), security.internalScope(), security.tokenVersion())))
                        .anyRequest().denyAll())
                // No cookie/session identity is accepted; the resource server retains its standard bearer CSRF behavior.
                .csrf(csrf -> csrf.ignoringRequestMatchers(request -> request.getHeader("Cookie") == null))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> ApiExceptionHandler.write(response, request, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, error) -> ApiExceptionHandler.write(response, request, 403, "DENIED")))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                        .authenticationEntryPoint((request, response, error) -> ApiExceptionHandler.write(response, request, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, error) -> ApiExceptionHandler.write(response, request, 403, "DENIED")))
                .addFilterBefore(new ApiBoundaryFilter(properties.limits()), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain closedRoutes(HttpSecurity http) throws Exception {
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> ApiExceptionHandler.write(response, request, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, error) -> ApiExceptionHandler.write(response, request, 403, "DENIED")))
                .build();
    }

    @Bean
    JwtDecoder apiJwtDecoder(ApiProperties properties, Environment environment) {
        var security = properties.security();
        var key = environment.getProperty("ingestion.local-capture.session-public-key");
        var decoder = key == null
                ? NimbusJwtDecoder.withJwkSetUri(security.jwkSetUri().toString()).build()
                : localDecoder(environment, security, key);
        decoder.setJwtValidator(validator(security, Clock.systemUTC()));
        return decoder;
    }

    private static NimbusJwtDecoder localDecoder(Environment environment, ApiProperties.Security security, String keyPath) {
        if (!environment.getProperty("ingestion.local-capture.enabled", Boolean.class, false)
                || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"https://pipesor.local/session".equals(security.issuer().toString())) {
            throw new IllegalStateException("Local JWT authority requires isolated loopback configuration");
        }
        try (var keyFile = Files.newInputStream(Path.of(keyPath), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            var encoded = keyFile.readNBytes(8193);
            if (encoded.length > 8192) throw new IllegalArgumentException();
            var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
            if (publicKey.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            return NimbusJwtDecoder.withPublicKey(publicKey).build();
        } catch (java.io.IOException | java.security.GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Valid local JWT signing public key required");
        }
    }

    public static OAuth2TokenValidator<Jwt> validator(ApiProperties.Security security, Clock clock) {
        var times = new JwtTimestampValidator(security.clockSkew());
        times.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(times, new JwtIssuerValidator(security.issuer().toString()), jwt -> {
            boolean valid;
            try {
                var caller = jwt.getClaimAsString(security.tokenVersion().equals("2.0") ? "azp" : "appid");
                var other = jwt.getClaimAsString(security.tokenVersion().equals("2.0") ? "appid" : "azp");
                valid = security.tokenVersion().equals(jwt.getClaimAsString("ver")) && canonicalUuid(caller)
                        && (other == null || other.equalsIgnoreCase(caller))
                        && (security.selfCallers().contains(UUID.fromString(caller)) || security.pipelineCallers().contains(UUID.fromString(caller)))
                        && jwt.getAudience().contains(security.audience())
                        && security.tenant().toString().equalsIgnoreCase(jwt.getClaimAsString("tid"))
                        && canonicalUuid(jwt.getClaimAsString("oid")) && jwt.getExpiresAt() != null
                        && jwt.getIssuedAt() != null && !jwt.getIssuedAt().isAfter(clock.instant().plus(security.clockSkew()))
                        && !"app".equals(jwt.getClaimAsString("idtyp"))
                        && (scope(jwt, security.selfScope()) || scope(jwt, security.internalScope()));
            } catch (RuntimeException malformed) { valid = false; }
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Invalid delegated identity", null));
        });
    }

    private static boolean caller(org.springframework.security.core.Authentication authentication, Set<UUID> allowed,
                                  String scope, String version) {
        if (!(authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken token)) { return false; }
        var jwt = token.getToken();
        return allowed.contains(UUID.fromString(jwt.getClaimAsString(version.equals("2.0") ? "azp" : "appid"))) && scope(jwt, scope);
    }
    private static boolean scope(Jwt jwt, String expected) {
        var scopes = jwt.getClaimAsString("scp");
        return scopes != null && Arrays.asList(scopes.split(" ")).contains(expected);
    }
    private static boolean canonicalUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException malformed) { return false; }
    }
}
