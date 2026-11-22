package org.pipesor.ingestion.capture.infrastructure.messaging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import org.pipesor.ingestion.capture.application.ports.BrokerPublisher;
import org.pipesor.ingestion.capture.application.ports.PublicationOutput;
import tools.jackson.databind.json.JsonMapper;

/** Local durable transport for the existing protected publication envelope. */
public final class LocalPublicationSpool implements BrokerPublisher {
    private static final JsonMapper JSON = JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final Path directory;

    public LocalPublicationSpool(Path directory) {
        this.directory = directory;
        try {
            if (!directory.isAbsolute() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IllegalArgumentException("Local publication spool requires an absolute directory with mode 0700");
            }
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("Local publication spool is unavailable");
        }
    }

    @Override
    public void publish(PublicationOutput output) {
        byte[] envelope = JSON.writeValueAsBytes(java.util.Map.of(
                "messageId", output.id().toString(), "correlationId", output.correlationId(),
                "applicationProperties", output.applicationProperties(),
                "contentType", output.contentType(), "bodyBase64", Base64.getEncoder().encodeToString(output.body())));
        Path target = directory.resolve(output.id() + ".json");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".capture-", ".pending",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(envelope);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            try {
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException duplicate) {
                try (var existing = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Arrays.equals(existing.readNBytes(envelope.length + 1), envelope)) {
                        throw new IOException("Conflicting local publication");
                    }
                }
            }
            try (var folder = FileChannel.open(directory, StandardOpenOption.READ)) {
                folder.force(true);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Local publication unavailable");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Readers ignore temporary files. */ }
            }
        }
    }
}
