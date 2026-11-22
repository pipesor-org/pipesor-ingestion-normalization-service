package org.pipesor.ingestion.configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.infrastructure.credentials.LocalAppSigningKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubAcquisitionControlTests {
    @Test
    void requiresAnOwnerOnlyFileWithThePinnedKey(@TempDir Path directory) throws Exception {
        var key = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate();
        var keyFile = Files.createFile(directory.resolve("key.pem"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(keyFile)));

        assertArrayEquals(key.getEncoded(), LocalAppSigningKey.read(keyFile, digest).getEncoded());
        assertThrows(CaptureFailure.class, () -> LocalAppSigningKey.read(keyFile, "0".repeat(64)));
        var alias = Files.createSymbolicLink(directory.resolve("alias.pem"), keyFile);
        assertThrows(CaptureFailure.class, () -> LocalAppSigningKey.read(alias, digest));
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(CaptureFailure.class, () -> LocalAppSigningKey.read(keyFile, digest));
    }
}
