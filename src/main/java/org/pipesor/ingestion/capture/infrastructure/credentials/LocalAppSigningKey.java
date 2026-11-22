package org.pipesor.ingestion.capture.infrastructure.credentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;

/** Key material explicitly pinned by the local operator. */
public final class LocalAppSigningKey {
    private LocalAppSigningKey() {}

    public static PrivateKey read(Path file, String expectedDigest) {
        byte[] bytes = null;
        try {
            var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (!file.isAbsolute() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !permissions.equals(Set.of(PosixFilePermission.OWNER_READ))
                        && !permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                    || !expectedDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(16385);
            }
            if (bytes.length > 16384 || !MessageDigest.isEqual(HexFormat.of().parseHex(expectedDigest),
                    MessageDigest.getInstance("SHA-256").digest(bytes))) {
                throw new IllegalArgumentException();
            }
            return KeyVaultAppMaterial.decodeSigningKey(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | NoSuchAlgorithmException | RuntimeException invalid) {
            throw new CaptureFailure(CaptureFailure.Reason.UNAVAILABLE);
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

}
