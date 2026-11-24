package org.pipesor.ingestion.auth.interfaces.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.pipesor.ingestion.auth.application.commands.LoginCommands;
import org.pipesor.ingestion.auth.application.ports.ConsentRecords;
import org.pipesor.ingestion.auth.application.queries.CurrentConsentQuery;
import org.pipesor.ingestion.auth.application.queries.LoginConsent;
import org.pipesor.ingestion.auth.application.services.GitHubLogin;
import org.pipesor.ingestion.auth.application.services.LoginConsentQueries;
import org.pipesor.ingestion.auth.application.services.LoginFailure;
import org.pipesor.ingestion.configuration.SessionProperties;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;
import org.pipesor.ingestion.shared.interfaces.rest.ApiExceptionHandler;
import org.pipesor.ingestion.shared.interfaces.rest.ApiFailure;
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
 * Login flow of the platform identity contract v1, §5, and the consent recorded at sign-in (ADR-029).
 * Codes, state, handoff values and tokens are never logged; the only URL carrying the handoff is the
 * dashboard redirect.
 */
@RestController
public final class AuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    static final String CONSENT_VERSION = "consent_version";
    private static final String DATE_PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$";
    private final GitHubLogin login;
    private final LoginConsentQueries consents;
    private final URI dashboard;

    public AuthController(GitHubLogin login, LoginConsentQueries consents, SessionProperties session) {
        this.login = login;
        this.consents = consents;
        this.dashboard = session.dashboardOrigin();
    }

    public record SessionRequest(String handoff) {
        @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("Unknown field"); }
        @Override public String toString() { return "SessionRequest[private]"; }
    }
    public record User(String githubUserId, String login) {}
    @Schema(name = "SessionConsent", description = "The consent recorded by the login that produced this handoff (ADR-029)")
    public record SessionConsent(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Accepted version of the Privacy Policy and Terms of Use", example = "2026-11-24")
            LocalDate documentsVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "When the sign-in checkbox was accepted (start of the login)")
            Instant acceptedAt) {}
    public record SessionResponse(String accessToken, Instant expiresAt, User user, String returnTo,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SessionConsent consent) {
        @Override public String toString() { return "SessionResponse[redacted]"; }
    }
    @Schema(name = "LoginConsent", description = "The caller's most recent, non-revoked consent given at sign-in (ADR-029)")
    public record ConsentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Accepted version of the Privacy Policy and Terms of Use", example = "2026-11-24")
            LocalDate documentsVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "When the sign-in checkbox was accepted (start of the login)")
            Instant acceptedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "How the consent was given")
            ConsentRecords.Source source) {}

    @GetMapping("/api/v1/auth/github/start")
    @Operation(summary = "Start the GitHub login", description = "Requires the version of the Privacy Policy and Terms of Use accepted on the sign-in screen "
            + "(ADR-029). Creates a one-time attempt (state, PKCE S256, 10 min) that stores the version and the acceptance time, and redirects to GitHub. "
            + "return_to is a dashboard path. A missing, malformed, repeated or future (later than tomorrow, UTC) consent_version redirects to "
            + "<dashboard>/auth/complete?error=consent_required without creating an attempt or contacting GitHub.")
    @Parameter(name = CONSENT_VERSION, in = ParameterIn.QUERY, required = true,
            description = "Accepted documents version, an ISO calendar date no later than tomorrow (UTC); exactly once",
            schema = @Schema(type = "string", format = "date", pattern = DATE_PATTERN, example = "2026-11-24"))
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirect to GitHub's authorize URL, or to <dashboard>/auth/complete?error=consent_required",
                    headers = @Header(name = HttpHeaders.LOCATION, description = "GitHub authorize URL or dashboard completion URL", schema = @Schema(type = "string", format = "uri"))),
            @ApiResponse(responseCode = "400", description = "Invalid return_to (INVALID_REQUEST)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
            @ApiResponse(responseCode = "503", description = "Login storage unavailable (LOGIN_UNAVAILABLE)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class)))
    })
    public ResponseEntity<Void> start(@RequestParam(name = "return_to", defaultValue = "/") String returnTo, HttpServletRequest request) {
        var values = request.getParameterValues(CONSENT_VERSION);
        var consentVersion = values != null && values.length == 1 ? values[0] : null;
        try {
            return redirect(login.start(new LoginCommands.Start(returnTo, consentVersion, ApiExceptionHandler.correlation(request))));
        } catch (LoginFailure failure) {
            if (failure.reason() != LoginFailure.Reason.CONSENT_REQUIRED) { throw failure; }
            LOG.info("GitHub login start refused reason={} correlationId={}", failure.reason(), ApiExceptionHandler.correlation(request));
            return failed(failure);
        }
    }

    @GetMapping("/api/v1/auth/github/callback")
    @Operation(summary = "GitHub login callback", description = "Consumes the attempt once, verifies the user, stores the user token in custody, appends the consent record "
            + "in the transaction that offers the handoff and redirects to <dashboard>/auth/complete?handoff=<one-time value> (60 s), or "
            + "?error=<invalid_state|access_denied|consent_required|unavailable>. If the consent record cannot be written the login fails with unavailable.")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
            @RequestParam(required = false) String error, HttpServletRequest request) {
        try {
            var handoff = login.callback(new LoginCommands.Callback(code, state, error, ApiExceptionHandler.correlation(request)));
            return redirect(dashboard.resolve("/auth/complete?handoff=" + handoff));
        } catch (LoginFailure failure) {
            var level = failure.reason() == LoginFailure.Reason.UNAVAILABLE ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO;
            LOG.atLevel(level).log("GitHub login callback failed reason={} correlationId={}", failure.reason(),
                    ApiExceptionHandler.correlation(request));
            return failed(failure);
        }
    }

    @PostMapping("/api/v1/auth/session")
    @Operation(summary = "Exchange the one-time handoff for a session token", description = "Single use, 60 s. The session token lasts at most one hour; there is no refresh token. "
            + "consent is the consent recorded by the login that produced the handoff.")
    public ResponseEntity<SessionResponse> session(@RequestBody SessionRequest body) {
        if (body == null) throw new LoginFailure(LoginFailure.Reason.INVALID_HANDOFF);
        var session = login.exchange(new LoginCommands.Exchange(body.handoff()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionResponse(session.accessToken(),
                session.expiresAt(), new User(session.githubUserId(), session.login()), session.returnPath(),
                new SessionConsent(session.consent().documentsVersion(), session.consent().acceptedAt())));
    }

    @GetMapping("/api/v1/auth/consent")
    @SecurityRequirement(name = "platformBearer")
    @Operation(summary = "Read the caller's current sign-in consent", description = "The most recent consent recorded at sign-in for the session subject, "
            + "if it is not revoked (ADR-029). Revocation is a manual operator procedure.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current consent", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsentResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid session token (UNAUTHENTICATED)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
            @ApiResponse(responseCode = "403", description = "Token without GitHub.Self (DENIED)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
            @ApiResponse(responseCode = "404", description = "No consent recorded, or the most recent one is revoked (CONSENT_NOT_FOUND)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class))),
            @ApiResponse(responseCode = "503", description = "Consent storage unavailable (LOGIN_UNAVAILABLE)", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiExceptionHandler.Problem.class)))
    })
    public ResponseEntity<ConsentResponse> consent(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        var identity = new ConnectionIdentity(UUID.fromString(jwt.getClaimAsString("tid")), UUID.fromString(jwt.getClaimAsString("oid")));
        LoginConsent current = consents.current(new CurrentConsentQuery(identity)).orElseThrow(() -> new ApiFailure(404, "CONSENT_NOT_FOUND"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ConsentResponse(current.documentsVersion(), current.acceptedAt(), current.source()));
    }

    @PostMapping("/api/v1/auth/logout")
    @SecurityRequirement(name = "platformBearer")
    @Operation(summary = "Log out", description = "Invalidates the server-side GitHub link generation of the session subject.")
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        login.logout(new LoginCommands.Logout(new ConnectionIdentity(UUID.fromString(jwt.getClaimAsString("tid")),
                UUID.fromString(jwt.getClaimAsString("oid")))));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<Void> failed(LoginFailure failure) {
        return redirect(dashboard.resolve("/auth/complete?error=" + failure.reason().name().toLowerCase(Locale.ROOT)));
    }

    private static ResponseEntity<Void> redirect(URI target) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target.toString())
                .cacheControl(CacheControl.noStore()).build();
    }
}
