package org.pipesor.ingestion.github.infrastructure.custody;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody.Failure;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Pair;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import static org.junit.jupiter.api.Assertions.*;

class FileCredentialCustodyTests {
    private static final SecretReference GENERATION = SecretReference.generationObject(UUID.randomUUID());
    private static final SecretReference PKCE = SecretReference.pkceObject(UUID.randomUUID());
    private static final Pair PAIR = new Pair("ghu_synthetic", "ghr_synthetic", Instant.parse("2026-11-22T10:00:00.123456789Z"), Instant.parse("2027-05-22T10:00:00Z"));

    @Test void storesExactOwnerOnlyVersionsAndReadsThemBack(@TempDir Path root) throws Exception {
        var directory = root.resolve("custody");
        var custody = new FileCredentialCustody(directory, 24000);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        var first = custody.savePair(GENERATION, PAIR);
        var second = custody.savePair(GENERATION, PAIR);
        assertNotEquals(first.version(), second.version());
        assertEquals(PAIR, custody.readPair(first));
        try (var files = Files.list(directory)) {
            for (var file : files.toList()) assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        var verifier = custody.saveVerifier(PKCE, "v".repeat(64));
        assertEquals("v".repeat(64), custody.readVerifier(verifier));
        assertFalse(custody.toString().contains(directory.toString()));
    }

    @Test void rejectsForeignOwnersWeakPermissionsAndTamperedFiles(@TempDir Path root) throws Exception {
        var directory = root.resolve("custody");
        var custody = new FileCredentialCustody(directory, 24000);
        var saved = custody.savePair(GENERATION, PAIR);
        expect(Failure.Reason.UNAVAILABLE, () -> custody.readPair(new SavedVersion(GENERATION, new SecretReference("0".repeat(32)))));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.readPair(new SavedVersion(GENERATION, new SecretReference("../escape"))));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.savePair(PKCE, PAIR));
        expect(Failure.Reason.INVALID_INPUT, () -> custody.saveVerifier(PKCE, "short"));
        var file = directory.resolve("github-generation-" + GENERATION.value().substring("generation:".length()) + "." + saved.version().value() + ".json");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        expect(Failure.Reason.UNAVAILABLE, () -> custody.readPair(saved));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(file, Files.readString(file).replace(GENERATION.value(), SecretReference.generationObject(UUID.randomUUID()).value()));
        expect(Failure.Reason.UNAVAILABLE, () -> custody.readPair(saved));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IllegalArgumentException.class, () -> new FileCredentialCustody(directory, 24000));
        assertThrows(IllegalArgumentException.class, () -> new FileCredentialCustody(Path.of("relative"), 24000));
    }

    private static void expect(Failure.Reason reason, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(Failure.class, action);
        assertEquals(reason, failure.reason());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("ghu_"));
    }
}
