package org.pipesor.ingestion.github.infrastructure.custody;

import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.models.KeyVaultSecret;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody.Failure;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Pair;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeyVaultCredentialCustodyTests {
    private static final URI VAULT = URI.create("https://custody-test.vault.azure.net");
    private static final SecretReference GENERATION = SecretReference.generationObject(UUID.fromString("3ac49904-cc7d-4f78-b27c-174f6725f161"));
    private static final SecretReference ATTEMPT = SecretReference.pkceObject(UUID.fromString("e2345352-65da-4915-a831-e78b1e4b0b17"));
    private static final String VERSION = "93f7d1088cbd4a73a80814bc998b6019";
    private static final String VERIFIER = "aZ09_-~.".repeat(8);
    private static final Instant ACCESS_EXPIRY = Instant.parse("2026-01-02T03:04:05.123456789Z");
    private static final Instant REFRESH_EXPIRY = Instant.parse("2026-04-06T09:10:11.987654321Z");
    private final SecretClient sdk = mock(SecretClient.class);
    private final KeyVaultCredentialCustody custody = configured();

    private KeyVaultCredentialCustody configured() {
        when(sdk.getVaultUrl()).thenReturn(VAULT.toString());
        return new KeyVaultCredentialCustody(sdk, VAULT, 24000);
    }
    private Pair pair() { return new Pair("ghu_syntheticAccess", "ghr_syntheticRefresh", ACCESS_EXPIRY, REFRESH_EXPIRY); }
    private SavedVersion pairVersion() { return new SavedVersion(GENERATION, new SecretReference(VERSION)); }
    private String pairBody() {
        return "{\"schemaVersion\":\"1\",\"kind\":\"pair\",\"owner\":\"" + GENERATION.value()
                + "\",\"accessToken\":\"ghu_syntheticAccess\",\"refreshToken\":\"ghr_syntheticRefresh\","
                + "\"accessExpiresAt\":\"" + ACCESS_EXPIRY + "\",\"refreshExpiresAt\":\"" + REFRESH_EXPIRY + "\"}";
    }
    private KeyVaultSecret response(String name, String version, String body) {
        var result = mock(KeyVaultSecret.class);
        var properties = mock(SecretProperties.class);
        when(result.getName()).thenReturn(name);
        when(result.getValue()).thenReturn(body);
        when(result.getProperties()).thenReturn(properties);
        when(properties.getName()).thenReturn(name);
        when(properties.getVersion()).thenReturn(version);
        when(properties.getId()).thenReturn(VAULT + "/secrets/" + name + "/" + version);
        return result;
    }
    private Failure expect(Failure.Reason reason, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(Failure.class, action);
        assertEquals(reason, failure.reason());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("synthetic"));
        assertFalse(failure.toString().contains("vault.azure.net"));
        assertFalse(failure.toString().contains(VERSION));
        return failure;
    }

    @Test
    void constructsTheRealManagedIdentitySdkWithoutContactingTheVault() {
        var client = KeyVaultCredentialCustody.secretClient(VAULT, Duration.ofSeconds(2), () -> new com.azure.identity.DefaultAzureCredentialBuilder().build());
        assertEquals(VAULT.toString(), client.getVaultUrl());
    }

    @Test void writesOneAtomicPairAndReadsOnlyTheConfirmedExactVersionWithoutRenewingIt() {
        when(sdk.setSecret(any(KeyVaultSecret.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, KeyVaultSecret.class);
            assertEquals("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", request.getName());
            assertTrue(request.getValue().contains("ghu_syntheticAccess"));
            assertTrue(request.getValue().contains("ghr_syntheticRefresh"));
            assertTrue(request.getValue().contains(ACCESS_EXPIRY.toString()));
            assertTrue(request.getValue().contains(REFRESH_EXPIRY.toString()));
            return response(request.getName(), VERSION, request.getValue());
        });
        var saved = custody.savePair(GENERATION, pair());
        assertEquals(pairVersion(), saved);
        when(sdk.getSecret("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION)).thenAnswer(ignored -> response("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION, pairBody()));
        assertEquals(pair(), custody.readPair(saved));
        assertEquals(pair(), custody.readPair(saved));
        verify(sdk, times(1)).setSecret(any(KeyVaultSecret.class));
        verify(sdk, times(2)).getSecret("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION);
        verify(sdk, never()).getSecret(anyString());
        assertEquals("KeyVaultCredentialCustody[configured]", custody.toString());
    }

    @Test void keepsExpiredAccessAndProviderNanosecondsForTheTrustedRefreshCaller() {
        when(sdk.getSecret("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION)).thenAnswer(ignored -> response("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION, pairBody()));
        var restored = custody.readPair(pairVersion());
        assertEquals(ACCESS_EXPIRY, restored.accessExpiresAt());
        assertEquals(REFRESH_EXPIRY, restored.refreshExpiresAt());
    }

    @Test void storesPkceForItsOwnAttemptAndNeverAsCredentials() {
        when(sdk.setSecret(any(KeyVaultSecret.class))).thenAnswer(invocation -> {
            var request = invocation.getArgument(0, KeyVaultSecret.class);
            return response(request.getName(), VERSION, request.getValue());
        });
        var saved = custody.saveVerifier(ATTEMPT, VERIFIER);
        assertEquals(ATTEMPT, saved.object());
        var argument = org.mockito.ArgumentCaptor.forClass(KeyVaultSecret.class);
        verify(sdk).setSecret(argument.capture());
        when(sdk.getSecret("github-pkce-e2345352-65da-4915-a831-e78b1e4b0b17", VERSION)).thenAnswer(ignored -> response("github-pkce-e2345352-65da-4915-a831-e78b1e4b0b17", VERSION, argument.getValue().getValue()));
        assertEquals(VERIFIER, custody.readVerifier(saved));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.readPair(saved));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.saveVerifier(GENERATION, VERIFIER));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.savePair(ATTEMPT, pair()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"vault", "name", "version", "propertiesName", "relativeId", "query", "encodedPath", "latest"})
    void rejectsForeignOrIncompatibleSdkIdentityBeforeReturningMaterial(String corruption) {
        var result = response("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION, pairBody());
        var properties = result.getProperties();
        switch (corruption) {
            case "vault" -> when(properties.getId()).thenReturn("https://other.vault.azure.net/secrets/github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161/" + VERSION);
            case "name" -> when(result.getName()).thenReturn("other-object");
            case "version" -> when(properties.getVersion()).thenReturn("differentVersion");
            case "propertiesName" -> when(properties.getName()).thenReturn("other-object");
            case "relativeId" -> when(properties.getId()).thenReturn("/secrets/github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161/" + VERSION);
            case "query" -> when(properties.getId()).thenReturn(VAULT + "/secrets/github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161/" + VERSION + "?other=1");
            case "encodedPath" -> when(properties.getId()).thenReturn(VAULT + "/secrets/github%2Dgeneration-3ac49904-cc7d-4f78-b27c-174f6725f161/" + VERSION);
            case "latest" -> when(properties.getVersion()).thenReturn("latest");
        }
        when(sdk.getSecret("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION)).thenReturn(result);
        expect(Failure.Reason.UNAVAILABLE, () -> custody.readPair(pairVersion()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "kind", "schema", "missingRefresh", "duplicate", "unknown", "invalidExpiry", "whitespaceToken", "oversize", "trailing"})
    void rejectsCrossOwnerOrMalformedSecretContents(String corruption) {
        var raw = switch (corruption) {
            case "owner" -> pairBody().replace(GENERATION.value(), SecretReference.generationObject(UUID.randomUUID()).value());
            case "kind" -> pairBody().replace("\"pair\"", "\"pkce\"");
            case "schema" -> pairBody().replace("\"1\"", "\"2\"");
            case "missingRefresh" -> pairBody().replace("\"refreshToken\":\"ghr_syntheticRefresh\",", "");
            case "duplicate" -> pairBody().replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"1\",\"schemaVersion\":\"1\"");
            case "unknown" -> pairBody().replace("{", "{\"unexpected\":true,");
            case "invalidExpiry" -> pairBody().replace(ACCESS_EXPIRY.toString(), "tomorrow");
            case "whitespaceToken" -> pairBody().replace("ghu_syntheticAccess", "ghu_synthetic Access");
            case "oversize" -> "x".repeat(24001);
            case "trailing" -> pairBody() + " {}";
            default -> throw new IllegalArgumentException();
        };
        when(sdk.getSecret("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION)).thenAnswer(ignored -> response("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION, raw));
        expect(Failure.Reason.UNAVAILABLE, () -> custody.readPair(pairVersion()));
    }

    @Test void rejectsUnapprovedObjectsPathVersionsAndInvalidPkceBeforeSdkIo() {
        expect(Failure.Reason.INVALID_INPUT, () -> custody.savePair(new SecretReference("generation:../escape"), pair()));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.readPair(new SavedVersion(GENERATION, new SecretReference("../version"))));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.saveVerifier(ATTEMPT, "too-short"));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.saveVerifier(ATTEMPT, "x".repeat(129)));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.saveVerifier(ATTEMPT, "x".repeat(43) + "+"));
        verify(sdk, never()).setSecret(any(KeyVaultSecret.class));
        verify(sdk, never()).getSecret(anyString(), anyString());
    }

    @Test void uncertainWriteHasOneAttemptAndDropsSensitiveSdkCause() {
        when(sdk.setSecret(any(KeyVaultSecret.class))).thenThrow(new IllegalStateException("synthetic-token private-vault-name " + VERSION));
        expect(Failure.Reason.UNAVAILABLE, () -> custody.savePair(GENERATION, pair()));
        verify(sdk, times(1)).setSecret(any(KeyVaultSecret.class));
        when(sdk.getSecret("github-generation-3ac49904-cc7d-4f78-b27c-174f6725f161", VERSION)).thenThrow(new IllegalStateException("synthetic-token"));
        expect(Failure.Reason.UNAVAILABLE, () -> custody.readPair(pairVersion()));
    }

    @Test void rejectsWrongWriteReceiptAndOversizedPayload() {
        when(sdk.setSecret(any(KeyVaultSecret.class))).thenAnswer(ignored -> response("other-object", VERSION, pairBody()));
        expect(Failure.Reason.UNAVAILABLE, () -> custody.savePair(GENERATION, pair()));
        var small = new KeyVaultCredentialCustody(sdk, VAULT, 1);
        expect(Failure.Reason.INVALID_INPUT, () -> small.savePair(GENERATION, pair()));
        verify(sdk, times(1)).setSecret(any(KeyVaultSecret.class));
    }

    @Test void refusesExternalIoInsideSqlTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            expect(Failure.Reason.INVALID_INPUT, () -> custody.savePair(GENERATION, pair()));
            expect(Failure.Reason.INVALID_INPUT, () -> custody.readPair(pairVersion()));
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verify(sdk, never()).setSecret(any(KeyVaultSecret.class));
        verify(sdk, never()).getSecret(anyString(), anyString());
    }

    @Test void configurationRejectsForeignVaultsAndBudgetsWithoutLeakingReferences() {
        assertThrowsExactly(IllegalArgumentException.class, () -> new KeyVaultCredentialCustody(sdk, URI.create("https://other.vault.azure.net"), 2000));
        assertThrowsExactly(IllegalArgumentException.class, () -> new KeyVaultCredentialCustody(sdk, VAULT, 25001));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.savePair(new SecretReference("generation:not-a-uuid"), pair()));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.savePair(ATTEMPT, pair()));
        java.util.function.Supplier<com.azure.core.credential.TokenCredential> credential = () -> new com.azure.identity.DefaultAzureCredentialBuilder().build();
        assertThrowsExactly(IllegalArgumentException.class, () -> KeyVaultCredentialCustody.secretClient(VAULT, Duration.ZERO, credential));
        assertThrowsExactly(IllegalArgumentException.class, () -> KeyVaultCredentialCustody.secretClient(URI.create("https://vault.example.invalid/path"), Duration.ofSeconds(2), credential));
    }
}
