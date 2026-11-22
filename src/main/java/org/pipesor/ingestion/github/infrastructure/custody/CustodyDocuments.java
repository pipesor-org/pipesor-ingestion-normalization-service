package org.pipesor.ingestion.github.infrastructure.custody;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody.Failure;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Pair;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody.Failure.Reason.*;

/** Owner-bound custody documents shared by the Key Vault and operator-file adapters. */
final class CustodyDocuments {
    private static final Set<String> PAIR_FIELDS = Set.of("schemaVersion", "kind", "owner", "accessToken", "refreshToken", "accessExpiresAt", "refreshExpiresAt");
    private static final Set<String> VERIFIER_FIELDS = Set.of("schemaVersion", "kind", "owner", "verifier");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private CustodyDocuments() { }

    /** Validates an object reference ({@code generation:<uuid>} or {@code pkce:<uuid>}) and returns its kind. */
    static String kind(SecretReference object) {
        if (object == null) { throw new IllegalArgumentException(); }
        var value = object.value();
        int colon = value.indexOf(':');
        if (colon < 1) { throw new IllegalArgumentException(); }
        var kind = value.substring(0, colon);
        var id = value.substring(colon + 1);
        if (!Set.of("generation", "pkce").contains(kind) || !UUID.fromString(id).toString().equals(id)) { throw new IllegalArgumentException(); }
        return kind;
    }

    /** A storage name derived only from the validated object reference, e.g. {@code github-pkce-<uuid>}. */
    static String name(SecretReference object, String expectedKind) {
        try {
            var kind = kind(object);
            if (!kind.equals(expectedKind)) { throw new IllegalArgumentException(); }
            return "github-" + kind + "-" + object.value().substring(kind.length() + 1);
        } catch (RuntimeException invalid) { throw new Failure(INVALID_INPUT); }
    }

    static String pair(SecretReference object, Pair pair) {
        if (pair == null) { throw new Failure(INVALID_INPUT); }
        return JSON.createObjectNode().put("schemaVersion", "1").put("kind", "pair").put("owner", object.value())
                .put("accessToken", pair.accessToken()).put("refreshToken", pair.refreshToken())
                .put("accessExpiresAt", pair.accessExpiresAt().toString()).put("refreshExpiresAt", pair.refreshExpiresAt().toString()).toString();
    }

    static String verifier(SecretReference object, String verifier) {
        if (!verifier(verifier)) { throw new Failure(INVALID_INPUT); }
        return JSON.createObjectNode().put("schemaVersion", "1").put("kind", "pkce").put("owner", object.value())
                .put("verifier", verifier).toString();
    }

    static Pair readPair(SecretReference owner, String raw, int maxBytes) {
        var body = read(owner, raw, "pair", PAIR_FIELDS, maxBytes);
        try {
            return new Pair(text(body, "accessToken"), text(body, "refreshToken"),
                    Instant.parse(text(body, "accessExpiresAt")), Instant.parse(text(body, "refreshExpiresAt")));
        } catch (RuntimeException invalid) { throw new Failure(UNAVAILABLE); }
    }

    static String readVerifier(SecretReference owner, String raw, int maxBytes) {
        var verifier = text(read(owner, raw, "pkce", VERIFIER_FIELDS, maxBytes), "verifier");
        if (!verifier(verifier)) { throw new Failure(UNAVAILABLE); }
        return verifier;
    }

    static void size(String raw, int maxBytes, Failure.Reason reason) {
        if (raw == null || raw.length() > maxBytes || raw.getBytes(StandardCharsets.UTF_8).length > maxBytes) { throw new Failure(reason); }
    }

    static boolean exactVersion(String version) {
        return version != null && !"latest".equalsIgnoreCase(version) && version.matches("[A-Za-z0-9-]{1,128}");
    }

    private static JsonNode read(SecretReference owner, String raw, String bodyKind, Set<String> fields, int maxBytes) {
        size(raw, maxBytes, UNAVAILABLE);
        try {
            var body = JSON.readTree(raw);
            if (body == null || !body.isObject() || body.propertyNames().size() != fields.size() || !fields.containsAll(body.propertyNames())
                    || !"1".equals(text(body, "schemaVersion")) || !bodyKind.equals(text(body, "kind"))
                    || !owner.value().equals(text(body, "owner"))) { throw new Failure(UNAVAILABLE); }
            return body;
        } catch (RuntimeException unavailable) { throw new Failure(UNAVAILABLE); }
    }

    private static boolean verifier(String value) { return value != null && value.matches("[A-Za-z0-9._~-]{43,128}"); }

    private static String text(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) { throw new Failure(UNAVAILABLE); }
        return value.asString();
    }
}
