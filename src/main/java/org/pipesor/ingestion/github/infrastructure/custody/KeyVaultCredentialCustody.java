package org.pipesor.ingestion.github.infrastructure.custody;

import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Pair;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody.Failure.Reason.*;

/** Exact owner-bound versions only; no retry, activation, deletion, recovery or latest-version lookup. */
public final class KeyVaultCredentialCustody implements GitHubCredentialCustody {
    private static final Set<String> PAIR_FIELDS = Set.of("schemaVersion", "kind", "owner", "accessToken", "refreshToken", "accessExpiresAt", "refreshExpiresAt");
    private static final Set<String> VERIFIER_FIELDS = Set.of("schemaVersion", "kind", "owner", "verifier");
    private final SecretClient client;
    private final URI vault;
    private final Map<SecretReference, String> names;
    private final int maxBytes;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public KeyVaultCredentialCustody(SecretClient client, URI approvedVault,
            Map<SecretReference, String> approvedObjects, int maxSecretBytes) {
        try {
            this.vault = validateVault(approvedVault);
            if (client == null || !vault.equals(validateVault(URI.create(client.getVaultUrl())))
                    || maxSecretBytes < 1 || maxSecretBytes > 25000) { throw new IllegalArgumentException(); }
            this.names = Map.copyOf(approvedObjects);
            var uniqueNames = new HashSet<String>();
            for (var entry : names.entrySet()) {
                validateObject(entry.getKey());
                if (!entry.getValue().matches("[A-Za-z0-9-]{1,127}")
                        || !uniqueNames.add(entry.getValue().toLowerCase(java.util.Locale.ROOT))) { throw new IllegalArgumentException(); }
            }
            this.client = client;
            this.maxBytes = maxSecretBytes;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid credential custody configuration"); }
    }

    /** Explicit managed identity and transport budgets; a write with a lost response must not be repeated automatically. */
    public static SecretClient managedIdentityClient(URI approvedVault, Duration timeout) {
        try {
            var vault = validateVault(approvedVault);
            if (timeout == null || timeout.isZero() || timeout.isNegative()) { throw new IllegalArgumentException(); }
            return new SecretClientBuilder().vaultUrl(vault.toString())
                    .credential(new ManagedIdentityCredentialBuilder().build())
                    .httpClient(HttpClient.createDefault(new HttpClientOptions().setConnectTimeout(timeout)
                            .setReadTimeout(timeout).setResponseTimeout(timeout).setWriteTimeout(timeout)))
                    .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                    .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE)).buildClient();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid credential custody client configuration"); }
    }

    @Override public SavedVersion savePair(SecretReference object, Pair pair) {
        var name = name(object, "generation");
        if (pair == null) { throw new Failure(INVALID_INPUT); }
        var body = json.createObjectNode().put("schemaVersion", "1").put("kind", "pair").put("owner", object.value())
                .put("accessToken", pair.accessToken()).put("refreshToken", pair.refreshToken())
                .put("accessExpiresAt", pair.accessExpiresAt().toString()).put("refreshExpiresAt", pair.refreshExpiresAt().toString()).toString();
        return save(object, name, body);
    }
    @Override public Pair readPair(SavedVersion version) {
        var body = read(version, "generation", "pair", PAIR_FIELDS);
        try {
            return new Pair(text(body, "accessToken"), text(body, "refreshToken"),
                    Instant.parse(text(body, "accessExpiresAt")), Instant.parse(text(body, "refreshExpiresAt")));
        } catch (RuntimeException invalid) { throw new Failure(UNAVAILABLE); }
    }
    @Override public SavedVersion saveVerifier(SecretReference object, String verifier) {
        var name = name(object, "pkce");
        if (!verifier(verifier)) { throw new Failure(INVALID_INPUT); }
        var body = json.createObjectNode().put("schemaVersion", "1").put("kind", "pkce").put("owner", object.value())
                .put("verifier", verifier).toString();
        return save(object, name, body);
    }
    @Override public String readVerifier(SavedVersion version) {
        var body = read(version, "pkce", "pkce", VERIFIER_FIELDS);
        var verifier = text(body, "verifier");
        if (!verifier(verifier)) { throw new Failure(UNAVAILABLE); }
        return verifier;
    }
    private SavedVersion save(SecretReference object, String name, String body) {
        size(body, INVALID_INPUT);
        noTransaction();
        try {
            var stored = client.setSecret(new KeyVaultSecret(name, body));
            var version = verify(stored, name, null);
            if (!body.equals(stored.getValue())) { throw new Failure(UNAVAILABLE); }
            return new SavedVersion(object, new SecretReference(version));
        } catch (RuntimeException unavailable) { throw new Failure(UNAVAILABLE); }
    }
    private JsonNode read(SavedVersion version, String ownerKind, String bodyKind, Set<String> fields) {
        if (version == null) { throw new Failure(INVALID_INPUT); }
        var name = name(version.object(), ownerKind);
        var exact = version.version().value();
        if (!exactVersion(exact)) { throw new Failure(INVALID_INPUT); }
        noTransaction();
        try {
            var stored = client.getSecret(name, exact);
            verify(stored, name, exact);
            var raw = stored.getValue();
            size(raw, UNAVAILABLE);
            var body = json.readTree(raw);
            if (body == null || !body.isObject() || (body.propertyNames().size() != fields.size() || !fields.containsAll(body.propertyNames()))
                    || !"1".equals(text(body, "schemaVersion")) || !bodyKind.equals(text(body, "kind"))
                    || !version.object().value().equals(text(body, "owner"))) { throw new Failure(UNAVAILABLE); }
            return body;
        } catch (RuntimeException unavailable) { throw new Failure(UNAVAILABLE); }
    }
    private String verify(KeyVaultSecret stored, String name, String expectedVersion) {
        if (stored == null || !name.equals(stored.getName()) || stored.getProperties() == null) { throw new Failure(UNAVAILABLE); }
        var properties = stored.getProperties();
        var version = properties.getVersion();
        var id = URI.create(properties.getId());
        if (!exactVersion(version) || (expectedVersion != null && !expectedVersion.equals(version))
                || !name.equals(properties.getName()) || !"https".equals(id.getScheme())
                || !vault.getHost().equalsIgnoreCase(id.getHost()) || id.getPort() != -1 || id.getUserInfo() != null
                || id.getQuery() != null || id.getFragment() != null
                || !("/secrets/" + name + "/" + version).equals(id.getRawPath())) { throw new Failure(UNAVAILABLE); }
        return version;
    }
    private String name(SecretReference object, String kind) {
        try {
            if (!validateObject(object).equals(kind)) { throw new IllegalArgumentException(); }
            var name = names.get(object);
            if (name == null) { throw new IllegalArgumentException(); }
            return name;
        } catch (RuntimeException invalid) { throw new Failure(INVALID_INPUT); }
    }
    private static String validateObject(SecretReference object) {
        if (object == null) { throw new IllegalArgumentException(); }
        var value = object.value();
        int colon = value.indexOf(':');
        if (colon < 1) { throw new IllegalArgumentException(); }
        var kind = value.substring(0, colon);
        var id = value.substring(colon + 1);
        if (!Set.of("generation", "pkce").contains(kind) || !UUID.fromString(id).toString().equals(id)) { throw new IllegalArgumentException(); }
        return kind;
    }
    private static URI validateVault(URI vault) {
        if (vault == null || !"https".equals(vault.getScheme()) || vault.getHost() == null || vault.getUserInfo() != null
                || vault.getPort() != -1 || vault.getQuery() != null || vault.getFragment() != null
                || !(vault.getRawPath().isEmpty() || vault.getRawPath().equals("/"))) { throw new IllegalArgumentException(); }
        return URI.create("https://" + vault.getHost().toLowerCase(java.util.Locale.ROOT));
    }
    private static boolean exactVersion(String version) {
        return version != null && !"latest".equalsIgnoreCase(version) && version.matches("[A-Za-z0-9-]{1,128}");
    }
    private static boolean verifier(String value) { return value != null && value.matches("[A-Za-z0-9._~-]{43,128}"); }
    private static String text(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) { throw new Failure(UNAVAILABLE); }
        return value.asString();
    }
    private void size(String raw, Failure.Reason reason) {
        if (raw == null || raw.length() > maxBytes || raw.getBytes(StandardCharsets.UTF_8).length > maxBytes) { throw new Failure(reason); }
    }
    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new Failure(INVALID_INPUT); }
    }
    @Override public String toString() { return "KeyVaultCredentialCustody[configured]"; }
}
