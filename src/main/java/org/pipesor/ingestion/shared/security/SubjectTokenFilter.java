package org.pipesor.ingestion.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Subject tokens of the platform identity contract (§4): on the listed routes a workload bearer must be
 * accompanied by exactly one unmodified session token, validated for this service's audience. The
 * session is exposed to handlers through {@link #subject(HttpServletRequest)}; it is never accepted alone.
 */
public final class SubjectTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Pipesor-Subject-Token";
    private static final String ATTRIBUTE = SubjectTokenFilter.class.getName() + ".subject";
    private final JwtDecoder decoder;
    private final List<RequestMatcher> routes;

    public SubjectTokenFilter(JwtDecoder decoder, List<RequestMatcher> routes) {
        this.decoder = Objects.requireNonNull(decoder);
        this.routes = List.copyOf(routes);
    }

    /** The verified session of the subject on a subject-token route; fails when absent. */
    public static Jwt subject(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof Jwt jwt) return jwt;
        throw new IllegalStateException("No verified subject token");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders(HEADER));
        boolean required = routes.stream().anyMatch(route -> route.matches(request));
        if (!required) {
            if (!headers.isEmpty()) { ProblemResponses.write(response, request, 401, "UNAUTHENTICATED"); return; }
            chain.doFilter(request, response);
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (headers.size() != 1 || !(authentication instanceof JwtAuthenticationToken workload)
                || workload.getToken().getClaimAsString("iss") == null
                || !workload.getToken().getClaimAsString("iss").startsWith("urn:pipesor:workload:")) {
            ProblemResponses.write(response, request, 401, "UNAUTHENTICATED");
            return;
        }
        final Jwt subject;
        try {
            subject = decoder.decode(headers.getFirst());
        } catch (JwtException invalid) {
            ProblemResponses.write(response, request, 401, "UNAUTHENTICATED");
            return;
        }
        if (!PlatformIds.SESSION_ISSUER.equals(subject.getClaimAsString("iss"))) {
            ProblemResponses.write(response, request, 401, "UNAUTHENTICATED");
            return;
        }
        request.setAttribute(ATTRIBUTE, subject);
        chain.doFilter(request, response);
    }
}
