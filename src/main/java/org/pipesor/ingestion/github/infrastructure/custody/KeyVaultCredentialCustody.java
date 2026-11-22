package org.pipesor.ingestion.github.infrastructure.custody;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import com.azure.core.http.policy.FixedDelayOptions;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Pair;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody.Failure.Reason.*;

/**
 * Azure custody in the dedicated OAuth custody vault. Secret names derive only from the validated
 * owner reference ({@code github-generation-<uuid>}, {@code github-pkce-<uuid>}); reads use exact
 * versions only; no retry, activation, deletion, recovery or latest-version lookup.
 */
public final class KeyVaultCredentialCustody implements GitHubCredentialCustody {
    private final SecretClient client;
    private final URI vault;
    private final int maxBytes;

    public KeyVaultCredentialCustody(SecretClient client, URI approvedVault, int maxSecretBytes) {
        try {
            this.vault = validateVault(approvedVault);
            if (client == null || !vault.equals(validateVault(URI.create(client.getVaultUrl())))
                    || maxSecretBytes < 1 || maxSecretBytes > 25000) { throw new IllegalArgumentException(); }
            this.client = client;
            this.maxBytes = maxSecretBytes;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid credential custody configuration"); }
    }

    /** Explicit workload credential and transport budgets; a write with a lost response must not be repeated automatically. */
    public static SecretClient secretClient(URI approvedVault, Duration timeout, Supplier<TokenCredential> credential) {
        try {
            var vault = validateVault(approvedVault);
            if (timeout == null || timeout.isZero() || timeout.isNegative()) { throw new IllegalArgumentException(); }
            return new SecretClientBuilder().vaultUrl(vault.toString())
                    .credential(Objects.requireNonNull(credential.get()))
                    .httpClient(HttpClient.createDefault(new HttpClientOptions().setConnectTimeout(timeout)
                            .setReadTimeout(timeout).setResponseTimeout(timeout).setWriteTimeout(timeout)))
                    .retryOptions(new RetryOptions(new FixedDelayOptions(0, Duration.ZERO)))
                    .httpLogOptions(new HttpLogOptions().setLogLevel(HttpLogDetailLevel.NONE)).buildClient();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid credential custody client configuration"); }
    }

    @Override public SavedVersion savePair(SecretReference object, Pair pair) {
        var name = CustodyDocuments.name(object, "generation");
        return save(object, name, CustodyDocuments.pair(object, pair));
    }
    @Override public Pair readPair(SavedVersion version) {
        return CustodyDocuments.readPair(owner(version), read(version, "generation"), maxBytes);
    }
    @Override public SavedVersion saveVerifier(SecretReference object, String verifier) {
        var name = CustodyDocuments.name(object, "pkce");
        return save(object, name, CustodyDocuments.verifier(object, verifier));
    }
    @Override public String readVerifier(SavedVersion version) {
        return CustodyDocuments.readVerifier(owner(version), read(version, "pkce"), maxBytes);
    }
    private SavedVersion save(SecretReference object, String name, String body) {
        CustodyDocuments.size(body, maxBytes, INVALID_INPUT);
        noTransaction();
        try {
            var stored = client.setSecret(new KeyVaultSecret(name, body));
            var version = verify(stored, name, null);
            if (!body.equals(stored.getValue())) { throw new Failure(UNAVAILABLE); }
            return new SavedVersion(object, new SecretReference(version));
        } catch (RuntimeException unavailable) { throw new Failure(UNAVAILABLE); }
    }
    private String read(SavedVersion version, String kind) {
        var name = CustodyDocuments.name(owner(version), kind);
        var exact = version.version().value();
        if (!CustodyDocuments.exactVersion(exact)) { throw new Failure(INVALID_INPUT); }
        noTransaction();
        try {
            var stored = client.getSecret(name, exact);
            verify(stored, name, exact);
            return stored.getValue();
        } catch (RuntimeException unavailable) { throw new Failure(UNAVAILABLE); }
    }
    private static SecretReference owner(SavedVersion version) {
        if (version == null) { throw new Failure(INVALID_INPUT); }
        return version.object();
    }
    private String verify(KeyVaultSecret stored, String name, String expectedVersion) {
        if (stored == null || !name.equals(stored.getName()) || stored.getProperties() == null) { throw new Failure(UNAVAILABLE); }
        var properties = stored.getProperties();
        var version = properties.getVersion();
        var id = URI.create(properties.getId());
        if (!CustodyDocuments.exactVersion(version) || (expectedVersion != null && !expectedVersion.equals(version))
                || !name.equals(properties.getName()) || !"https".equals(id.getScheme())
                || !vault.getHost().equalsIgnoreCase(id.getHost()) || id.getPort() != -1 || id.getUserInfo() != null
                || id.getQuery() != null || id.getFragment() != null
                || !("/secrets/" + name + "/" + version).equals(id.getRawPath())) { throw new Failure(UNAVAILABLE); }
        return version;
    }
    private static URI validateVault(URI vault) {
        if (vault == null || !"https".equals(vault.getScheme()) || vault.getHost() == null || vault.getUserInfo() != null
                || vault.getPort() != -1 || vault.getQuery() != null || vault.getFragment() != null
                || !(vault.getRawPath().isEmpty() || vault.getRawPath().equals("/"))) { throw new IllegalArgumentException(); }
        return URI.create("https://" + vault.getHost().toLowerCase(java.util.Locale.ROOT));
    }
    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new Failure(INVALID_INPUT); }
    }
    @Override public String toString() { return "KeyVaultCredentialCustody[configured]"; }
}
