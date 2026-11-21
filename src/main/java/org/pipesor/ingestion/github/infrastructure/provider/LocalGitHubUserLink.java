package org.pipesor.ingestion.github.infrastructure.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.application.ports.Observation;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub.Identity;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.GitHubLink;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.pipesor.ingestion.github.application.services.GitHubAccessFailure.Reason.*;

/** Isolated file custody for an opt-in expiring GitHub App user grant. */
public final class LocalGitHubUserLink implements CurrentGitHubLink {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();
    private static final Set<String> FIELDS = Set.of("app", "tenant", "actor", "githubUser", "generation", "issuedAt",
            "expiresAt", "accessToken");
    private final Path file;
    private final Clock clock;
    private final String app;

    public LocalGitHubUserLink(Path file, Clock clock, String app) {
        if (!file.isAbsolute()) throw new IllegalArgumentException("Absolute local user link path required");
        this.file = file;
        this.clock = clock;
        this.app = app;
    }

    @Override
    public Observation<AccessContext, Optional<GitHubLink>> read(AccessContext context) {
        var linked = load(context);
        return new Observation<>(context, linked.map(Grant::link), clock.instant());
    }

    public GitHubReadProvider.Credential credential(Identity identity) {
        var grant = load(identity.context()).filter(value -> value.link().equals(identity.link()))
                .orElseThrow(() -> new GitHubAccessFailure(CONNECTION_REQUIRED));
        return new GitHubReadProvider.Credential(identity, grant.token(), grant.expires());
    }

    private Optional<Grant> load(AccessContext context) {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rw-------"))) throw new IllegalArgumentException();
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var bytes = input.readNBytes(16385);
                if (bytes.length > 16384) throw new IllegalArgumentException();
                var object = JSON.readTree(bytes);
                if (!FIELDS.equals(Set.copyOf(object.propertyNames())) || !app.equals(object.get("app").asString())) {
                    throw new IllegalArgumentException();
                }
                var tenant = UUID.fromString(object.get("tenant").asString()).toString();
                var owner = tenant + ":" + UUID.fromString(object.get("actor").asString());
                if (!tenant.equals(context.tenantId()) || !owner.equals(context.subjectRef())) return Optional.empty();
                var now = clock.instant();
                var issued = Instant.parse(object.get("issuedAt").asString());
                var expires = Instant.parse(object.get("expiresAt").asString());
                var token = object.get("accessToken").asString();
                var user = object.get("githubUser").asString();
                var generation = UUID.fromString(object.get("generation").asString());
                if (!expires.isAfter(now)) return Optional.empty();
                if (issued.isAfter(now) || expires.isAfter(issued.plusSeconds(28800))
                        || !token.matches("ghu_[A-Za-z0-9_]{10,2048}") || !user.matches("[1-9][0-9]*")) {
                    throw new IllegalArgumentException();
                }
                return Optional.of(new Grant(new GitHubLink(tenant, owner, user, generation.toString(), issued, generation),
                        token, expires));
            }
        } catch (IOException | RuntimeException unsafe) {
            throw new GitHubAccessFailure(UNAVAILABLE);
        }
    }

    private record Grant(GitHubLink link, String token, Instant expires) {
        @Override public String toString() { return "LocalUserGrant[private]"; }
    }
}
