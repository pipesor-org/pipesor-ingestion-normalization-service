package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The GitHub App, its operator authority file and the user-authorization (login) settings.
 * File-based material ({@code *-file}, {@code custody-directory}) is accepted only under the
 * {@code local} profile; Key Vault references are the {@code azure} equivalents.
 */
@ConfigurationProperties("pipesor.github")
public record GitHubProperties(
        String appId, URI apiBaseUrl, URI webBaseUrl, String apiVersion, Path authorityFile, String captureDomain,
        URI callbackUrl, String clientId,
        Path privateKeyFile, String privateKeySha256, Path clientSecretFile, Path webhookSecretFile, Path custodyDirectory,
        URI keyVaultUrl, String privateKeySecretName, String privateKeySecretVersion,
        String clientSecretName, String clientSecretVersion, String webhookSecretName, String webhookSecretVersion,
        URI custodyVaultUrl, boolean webhookEnabled, Limits limits) {

    public GitHubProperties {
        requireDecimal(appId, "pipesor.github.app-id");
        Objects.requireNonNull(apiBaseUrl, "pipesor.github.api-base-url is required");
        Objects.requireNonNull(webBaseUrl, "pipesor.github.web-base-url is required");
        if (apiVersion == null || !apiVersion.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw new IllegalArgumentException("pipesor.github.api-version must be a dated GitHub API version");
        }
        Objects.requireNonNull(authorityFile, "pipesor.github.authority-file is required");
        if (captureDomain == null || !captureDomain.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("pipesor.github.capture-domain is required");
        }
        Objects.requireNonNull(callbackUrl, "pipesor.github.callback-url is required");
        if (clientId != null && !clientId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("pipesor.github.client-id is invalid");
        }
        boolean fileKey = privateKeyFile != null;
        boolean vaultKey = keyVaultUrl != null;
        if (fileKey == vaultKey) {
            throw new IllegalArgumentException("Exactly one of pipesor.github.private-key-file or pipesor.github.key-vault-url is required");
        }
        if (fileKey && (privateKeySha256 == null || !privateKeySha256.matches("[0-9a-f]{64}") || clientSecretFile == null)) {
            throw new IllegalArgumentException("pipesor.github.private-key-sha256 and pipesor.github.client-secret-file are required with a file key");
        }
        if (vaultKey && (privateKeySecretName == null || privateKeySecretVersion == null
                || clientSecretName == null || clientSecretVersion == null)) {
            throw new IllegalArgumentException("Key Vault App material requires exact private key and client secret names and versions");
        }
        if (webhookEnabled && (fileKey ? webhookSecretFile == null : webhookSecretName == null || webhookSecretVersion == null)) {
            throw new IllegalArgumentException("pipesor.github.webhook-enabled requires a webhook secret");
        }
        if ((custodyDirectory == null) == (custodyVaultUrl == null)) {
            throw new IllegalArgumentException("Exactly one of pipesor.github.custody-directory or pipesor.github.custody-vault-url is required");
        }
        Objects.requireNonNull(limits, "pipesor.github.limits is required");
    }

    public boolean fileMaterial() { return privateKeyFile != null; }

    /** Bounded outbound behavior; defaults live in application.yml. */
    public record Limits(Duration userTimeout, int userPageSize, int userMaxPages, int userMaxItems, int userMaxResponseBytes,
            Duration machineTimeout, int machinePageSize, int machineMaxPages, int machineMaxJobs, int machineMaxResponseBytes,
            int machineMaxLogBytes, Set<String> downloadHosts, Duration appJwtTtl, Duration appJwtIssuedAtSkew,
            Duration oauthTimeout, int oauthMaxResponseBytes, Duration vaultTimeout, int maxSecretBytes,
            int authorityMaxFileBytes, int authorityMaxEntries, int maxRunPages,
            Duration machineScopeTimeout, int machineScopeMaxRequestBytes, int machineScopeMaxInFlight,
            Duration webhookTimeout, int webhookMaxBodyBytes, int webhookMaxHeaderChars, int webhookMaxInFlight) {
        public Limits {
            for (var duration : new Duration[] {userTimeout, machineTimeout, appJwtTtl, oauthTimeout, vaultTimeout,
                    machineScopeTimeout, webhookTimeout}) {
                if (duration == null || duration.isNegative() || duration.isZero()) {
                    throw new IllegalArgumentException("Positive pipesor.github.limits durations are required");
                }
            }
            if (appJwtIssuedAtSkew == null || appJwtIssuedAtSkew.isNegative()) {
                throw new IllegalArgumentException("pipesor.github.limits.app-jwt-issued-at-skew is required");
            }
            for (int value : new int[] {userPageSize, userMaxPages, userMaxItems, userMaxResponseBytes, machinePageSize,
                    machineMaxPages, machineMaxJobs, machineMaxResponseBytes, machineMaxLogBytes, oauthMaxResponseBytes,
                    maxSecretBytes, authorityMaxFileBytes, authorityMaxEntries, maxRunPages, machineScopeMaxRequestBytes,
                    machineScopeMaxInFlight, webhookMaxBodyBytes, webhookMaxHeaderChars, webhookMaxInFlight}) {
                if (value < 1 || value == Integer.MAX_VALUE) throw new IllegalArgumentException("Positive pipesor.github.limits are required");
            }
            downloadHosts = downloadHosts == null ? Set.of() : Set.copyOf(downloadHosts);
        }
    }

    private static void requireDecimal(String value, String name) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw new IllegalArgumentException(name + " is required");
    }

    @Override public String toString() { return "GitHubProperties[configured]"; }
}
