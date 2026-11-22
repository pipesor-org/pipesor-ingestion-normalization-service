package org.pipesor.ingestion.auth.interfaces.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.pipesor.ingestion.auth.application.commands.LoginCommands;
import org.pipesor.ingestion.auth.application.services.GitHubLogin;
import org.pipesor.ingestion.auth.application.services.LoginFailure;
import org.pipesor.ingestion.configuration.SessionProperties;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;
import org.pipesor.ingestion.shared.interfaces.rest.ApiExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Login flow of the platform identity contract v1, §5. Codes, state, handoff values and tokens are
 * never logged; the only URL carrying the handoff is the dashboard redirect.
 */
@RestController
public final class AuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    private final GitHubLogin login;
    private final URI dashboard;

    public AuthController(GitHubLogin login, SessionProperties session) {
        this.login = login;
        this.dashboard = session.dashboardOrigin();
    }

    public record SessionRequest(String handoff) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field"); }
        @Override public String toString() { return "SessionRequest[private]"; }
    }
    public record User(String githubUserId, String login) {}
    public record SessionResponse(String accessToken, Instant expiresAt, User user, String returnTo) {
        @Override public String toString() { return "SessionResponse[redacted]"; }
    }

    @GetMapping("/api/v1/auth/github/start")
    @Operation(summary = "Start the GitHub login", description = "Creates a one-time attempt (state, PKCE S256, 10 min) and redirects to GitHub. return_to is a dashboard path.")
    public ResponseEntity<Void> start(@RequestParam(name = "return_to", defaultValue = "/") String returnTo, HttpServletRequest request) {
        var target = login.start(new LoginCommands.Start(returnTo, ApiExceptionHandler.correlation(request)));
        return redirect(target);
    }

    @GetMapping("/api/v1/auth/github/callback")
    @Operation(summary = "GitHub login callback", description = "Consumes the attempt once, verifies the user, installation and repository, stores the user token in custody and redirects to <dashboard>/auth/complete?handoff=<one-time value> (60 s), or ?error=<code>.")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
            @RequestParam(required = false) String error, HttpServletRequest request) {
        try {
            var handoff = login.callback(new LoginCommands.Callback(code, state, error, ApiExceptionHandler.correlation(request)));
            return redirect(dashboard.resolve("/auth/complete?handoff=" + handoff));
        } catch (LoginFailure failure) {
            var level = failure.reason() == LoginFailure.Reason.UNAVAILABLE ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO;
            LOG.atLevel(level).log("GitHub login callback failed reason={} correlationId={}", failure.reason(),
                    ApiExceptionHandler.correlation(request));
            return redirect(dashboard.resolve("/auth/complete?error=" + failure.reason().name().toLowerCase(Locale.ROOT)));
        }
    }

    @PostMapping("/api/v1/auth/session")
    @Operation(summary = "Exchange the one-time handoff for a session token", description = "Single use, 60 s. The session token lasts at most one hour; there is no refresh token.")
    public ResponseEntity<SessionResponse> session(@RequestBody SessionRequest body) {
        if (body == null) throw new LoginFailure(LoginFailure.Reason.INVALID_HANDOFF);
        var session = login.exchange(new LoginCommands.Exchange(body.handoff()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionResponse(session.accessToken(),
                session.expiresAt(), new User(session.githubUserId(), session.login()), session.returnPath()));
    }

    @PostMapping("/api/v1/auth/logout")
    @SecurityRequirement(name = "platformBearer")
    @Operation(summary = "Log out", description = "Invalidates the server-side GitHub link generation of the session subject.")
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        login.logout(new LoginCommands.Logout(new ConnectionIdentity(UUID.fromString(jwt.getClaimAsString("tid")),
                UUID.fromString(jwt.getClaimAsString("oid")))));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<Void> redirect(URI target) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target.toString())
                .cacheControl(CacheControl.noStore()).build();
    }
}
