package org.pipesor.ingestion.auth.application.commandhandlers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.pipesor.ingestion.auth.application.commands.LoginCommands.*;
import org.pipesor.ingestion.auth.application.ports.*;
import org.pipesor.ingestion.auth.application.services.GitHubLogin;
import org.pipesor.ingestion.auth.application.services.LoginFailure;
import org.pipesor.ingestion.github.application.commands.ConnectionCommands;
import org.pipesor.ingestion.github.application.ports.ConnectionStatusReader;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens;
import org.pipesor.ingestion.github.application.queries.ConnectionStatus;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.application.services.GitHubConnections;
import org.pipesor.ingestion.github.domain.exceptions.ConnectionFailure;
import org.pipesor.ingestion.github.domain.model.aggregates.GitHubConnection.Outcome;
import org.pipesor.ingestion.github.domain.model.valueobjects.ConnectionIdentity;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.StateDigest;
import org.pipesor.ingestion.shared.security.PlatformIds;

import static org.pipesor.ingestion.auth.application.services.LoginFailure.Reason.*;

/**
 * Server-side GitHub App user authorization with one-time state and PKCE S256 (ADR-020, ADR-022).
 * The callback links the verified GitHub user through the ADR-020 connection model (attempt,
 * generation, custody) before a one-time handoff can be exchanged for a session token.
 */
public final class GitHubLoginHandler implements GitHubLogin {
    static final Duration ATTEMPT_LIFETIME = Duration.ofMinutes(10);
    static final Duration HANDOFF_LIFETIME = Duration.ofSeconds(60);
    private static final Duration MAX_USER_TOKEN_LIFETIME = Duration.ofHours(8).plusMinutes(1);
    private static final String RETURN_PATH = "/(?!/)[A-Za-z0-9/_.~-]{0,255}";
    private static final String OPAQUE = "[A-Za-z0-9_-]{43}";

    private final UUID tenant;
    private final LoginAttempts attempts;
    private final GitHubAuthorization authorization;
    private final GitHubUserTokens tokens;
    private final GitHubLoginDirectory directory;
    private final HumanAdmissions admissions;
    private final GitHubCredentialCustody custody;
    private final GitHubConnections connections;
    private final ConnectionStatusReader statuses;
    private final SessionIssuer sessions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public GitHubLoginHandler(UUID tenant, LoginAttempts attempts, GitHubAuthorization authorization, GitHubUserTokens tokens,
            GitHubLoginDirectory directory, HumanAdmissions admissions, GitHubCredentialCustody custody,
            GitHubConnections connections, ConnectionStatusReader statuses, SessionIssuer sessions, Clock clock) {
        this.tenant = Objects.requireNonNull(tenant); this.attempts = Objects.requireNonNull(attempts);
        this.authorization = Objects.requireNonNull(authorization); this.tokens = Objects.requireNonNull(tokens);
        this.directory = Objects.requireNonNull(directory); this.admissions = Objects.requireNonNull(admissions);
        this.custody = Objects.requireNonNull(custody); this.connections = Objects.requireNonNull(connections);
        this.statuses = Objects.requireNonNull(statuses); this.sessions = Objects.requireNonNull(sessions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public URI start(Start command) {
        if (command == null || command.returnPath() == null || !command.returnPath().matches(RETURN_PATH)
                || command.correlationId() == null || command.correlationId().isBlank()) {
            throw new LoginFailure(INVALID_REQUEST);
        }
        var state = opaque(32);
        var verifier = opaque(64);
        var id = UUID.randomUUID();
        var now = clock.instant();
        try {
            var saved = custody.saveVerifier(SecretReference.pkceObject(id), verifier);
            attempts.create(new LoginAttempts.NewAttempt(id, digest(state), saved, command.returnPath(),
                    now.plus(ATTEMPT_LIFETIME), command.correlationId(), now));
            return authorization.authorizeUrl(state, challenge(verifier));
        } catch (RuntimeException unavailable) {
            throw new LoginFailure(UNAVAILABLE);
        }
    }

    @Override public String callback(Callback command) {
        if (command == null || command.state() == null || !command.state().matches(OPAQUE)) { throw new LoginFailure(INVALID_STATE); }
        var state = digest(command.state());
        LoginAttempts.Claimed claimed;
        try { claimed = attempts.claim(state, clock.instant()).orElseThrow(() -> new LoginFailure(INVALID_STATE)); }
        catch (LoginFailure invalid) { throw invalid; }
        catch (RuntimeException unavailable) { throw new LoginFailure(UNAVAILABLE); }
        try {
            if (command.error() != null || command.code() == null) { throw new LoginFailure(ACCESS_DENIED); }
            var pair = tokens.exchange(new GitHubUserTokens.AuthorizationCode(command.code(), custody.readVerifier(claimed.verifier())));
            if (pair.accessExpiresAt().isAfter(clock.instant().plus(MAX_USER_TOKEN_LIFETIME))) { throw new LoginFailure(UNAVAILABLE); }
            var facts = directory.read(pair.accessToken(), pair.accessExpiresAt());
            // Same checks as the former operator link script: an active installation of this App whose
            // account and repository are admitted for the tenant, with the user administering that repository.
            boolean admitted = facts.repositories().stream().anyMatch(repository -> repository.readable()
                    && repository.administrator() && admissions.admitted(tenant, repository.accountId(), repository.target()));
            if (!admitted) { throw new LoginFailure(NOT_ADMITTED); }
            long githubUserId = Long.parseLong(facts.githubUserId());
            var identity = new ConnectionIdentity(tenant, PlatformIds.human(githubUserId));
            var connection = connection(identity);
            var generationId = UUID.randomUUID();
            var generationObject = SecretReference.generationObject(generationId);
            connections.prepare(new ConnectionCommands.Prepare(connection.connectionId(), claimed.id(), generationId,
                    connection.revision(), connection.activeGenerationId(), state, claimed.expiresAt(), generationObject,
                    SecretReference.pkceObject(claimed.id()), claimed.returnPath(), claimed.correlationId()));
            connections.ready(new ConnectionCommands.Ready(connection.connectionId(), claimed.id(), claimed.verifier()));
            require(connections.claim(new ConnectionCommands.Claim(connection.connectionId(), claimed.id(), identity, state)) == Outcome.CLAIMED);
            var credential = custody.savePair(generationObject, pair);
            require(connections.activate(new ConnectionCommands.Activate(connection.connectionId(), claimed.id(),
                    facts.githubUserId(), credential, pair.accessExpiresAt(), pair.refreshExpiresAt())) == Outcome.ACTIVATED);
            var handoff = opaque(32);
            attempts.offerHandoff(claimed.id(), new LoginAttempts.Handoff(digest(handoff), clock.instant().plus(HANDOFF_LIFETIME),
                    connection.connectionId(), generationId, facts.githubUserId(), facts.login(), claimed.returnPath()), clock.instant());
            return handoff;
        } catch (RuntimeException failure) {
            try { attempts.fail(claimed.id(), clock.instant()); } catch (RuntimeException ignored) { /* The claim already prevents reuse. */ }
            throw translate(failure);
        }
    }

    @Override public Session exchange(Exchange command) {
        if (command == null || command.handoff() == null || !command.handoff().matches(OPAQUE)) { throw new LoginFailure(INVALID_HANDOFF); }
        LoginAttempts.Handoff handoff;
        try { handoff = attempts.consumeHandoff(digest(command.handoff()), clock.instant()).orElseThrow(() -> new LoginFailure(INVALID_HANDOFF)); }
        catch (LoginFailure invalid) { throw invalid; }
        catch (RuntimeException unavailable) { throw new LoginFailure(UNAVAILABLE); }
        try {
            long githubUserId = Long.parseLong(handoff.githubUserId());
            var current = statuses.find(new ConnectionIdentity(tenant, PlatformIds.human(githubUserId)));
            if (current.isEmpty() || !current.get().connectionId().equals(handoff.connectionId())
                    || !handoff.generationId().equals(current.get().activeGenerationId())) {
                throw new LoginFailure(INVALID_HANDOFF);
            }
            var issued = sessions.issue(githubUserId);
            return new Session(issued.accessToken(), issued.expiresAt(), handoff.githubUserId(), handoff.login(), handoff.returnPath());
        } catch (LoginFailure invalid) { throw invalid; }
        catch (RuntimeException unavailable) { throw new LoginFailure(UNAVAILABLE); }
    }

    @Override public void logout(Logout command) {
        Objects.requireNonNull(command);
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                var current = statuses.find(command.identity());
                if (current.isEmpty() || current.get().activeGenerationId() == null) { return; }
                try {
                    connections.disconnect(new ConnectionCommands.Disconnect(current.get().connectionId(), current.get().revision()));
                    return;
                } catch (ConnectionFailure conflict) {
                    if (conflict.reason() != ConnectionFailure.Reason.CONFLICT) { throw conflict; }
                }
            }
            throw new LoginFailure(UNAVAILABLE);
        } catch (LoginFailure failure) { throw failure; }
        catch (RuntimeException unavailable) { throw new LoginFailure(UNAVAILABLE); }
    }

    private ConnectionStatus connection(ConnectionIdentity identity) {
        var current = statuses.find(identity);
        if (current.isPresent()) { return current.get(); }
        try { connections.create(new ConnectionCommands.Create(UUID.randomUUID(), identity)); }
        catch (ConnectionFailure conflict) { if (conflict.reason() != ConnectionFailure.Reason.CONFLICT) { throw conflict; } }
        return statuses.find(identity).orElseThrow(() -> new LoginFailure(UNAVAILABLE));
    }

    private static LoginFailure translate(RuntimeException failure) {
        if (failure instanceof LoginFailure login) { return login; }
        if (failure instanceof GitHubUserTokens.Failure provider && provider.reason() == GitHubUserTokens.Failure.Reason.REJECTED) {
            return new LoginFailure(ACCESS_DENIED);
        }
        if (failure instanceof GitHubAccessFailure access && access.reason() == GitHubAccessFailure.Reason.CONNECTION_REQUIRED) {
            return new LoginFailure(ACCESS_DENIED);
        }
        return new LoginFailure(UNAVAILABLE);
    }

    private static void require(boolean expected) { if (!expected) { throw new LoginFailure(UNAVAILABLE); } }

    private String opaque(int bytes) {
        var value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static StateDigest digest(String value) { return new StateDigest(sha256(value)); }

    static String challenge(String verifier) { return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier)); }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
