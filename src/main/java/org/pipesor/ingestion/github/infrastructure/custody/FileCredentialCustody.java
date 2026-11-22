package org.pipesor.ingestion.github.infrastructure.custody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody;
import org.pipesor.ingestion.github.application.ports.GitHubUserTokens.Pair;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference;
import org.pipesor.ingestion.github.domain.model.valueobjects.SecretReference.SavedVersion;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.pipesor.ingestion.github.application.ports.GitHubCredentialCustody.Failure.Reason.*;

/**
 * Operator-file custody for the {@code local} profile: an owner-only (0700) directory holding one
 * immutable owner-only (0600) file per saved version, {@code <name>.<version>.json}. Contents and
 * paths are never logged; failures carry no cause.
 */
public final class FileCredentialCustody implements GitHubCredentialCustody {
    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private final Path directory;
    private final int maxBytes;

    public FileCredentialCustody(Path directory, int maxSecretBytes) {
        try {
            if (directory == null || !directory.isAbsolute() || maxSecretBytes < 1) { throw new IllegalArgumentException(); }
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY));
                Files.setPosixFilePermissions(directory, DIRECTORY);
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).equals(DIRECTORY)) {
                throw new IllegalArgumentException();
            }
            this.directory = directory;
            this.maxBytes = maxSecretBytes;
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("The local custody directory must be an owner-only (0700) directory");
        }
    }

    @Override public SavedVersion savePair(SecretReference object, Pair pair) {
        return save(object, CustodyDocuments.name(object, "generation"), CustodyDocuments.pair(object, pair));
    }
    @Override public Pair readPair(SavedVersion version) {
        return CustodyDocuments.readPair(owner(version), read(version, "generation"), maxBytes);
    }
    @Override public SavedVersion saveVerifier(SecretReference object, String verifier) {
        return save(object, CustodyDocuments.name(object, "pkce"), CustodyDocuments.verifier(object, verifier));
    }
    @Override public String readVerifier(SavedVersion version) {
        return CustodyDocuments.readVerifier(owner(version), read(version, "pkce"), maxBytes);
    }

    private SavedVersion save(SecretReference object, String name, String body) {
        CustodyDocuments.size(body, maxBytes, INVALID_INPUT);
        noTransaction();
        var version = UUID.randomUUID().toString().replace("-", "");
        var target = directory.resolve(name + "." + version + ".json");
        var temporary = directory.resolve("." + name + "." + version + ".tmp");
        try {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE));
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(java.nio.ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return new SavedVersion(object, new SecretReference(version));
        } catch (IOException | RuntimeException unavailable) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Owner-only leftover without a version reference. */ }
            throw new Failure(UNAVAILABLE);
        }
    }

    private String read(SavedVersion version, String kind) {
        var name = CustodyDocuments.name(owner(version), kind);
        var exact = version.version().value();
        if (!CustodyDocuments.exactVersion(exact)) { throw new Failure(INVALID_INPUT); }
        noTransaction();
        var file = directory.resolve(name + "." + exact + ".json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(FILE)) { throw new Failure(UNAVAILABLE); }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                var bytes = input.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) { throw new Failure(UNAVAILABLE); }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Failure failure) { throw failure; }
        catch (IOException | RuntimeException unavailable) { throw new Failure(UNAVAILABLE); }
    }

    private static SecretReference owner(SavedVersion version) {
        if (version == null) { throw new Failure(INVALID_INPUT); }
        return version.object();
    }

    private static void noTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new Failure(INVALID_INPUT); }
    }

    @Override public String toString() { return "FileCredentialCustody[configured]"; }
}
