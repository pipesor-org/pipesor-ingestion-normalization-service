package org.pipesor.ingestion.normalization.domain.model.valueobjects;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure.Reason.INVALID_IDENTITY;

/** Public owner rules: Pipeline's existing references and the locally approved Log/Event evidence rule. */
public final class LogicalIds {
    private static final UUID URL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private LogicalIds() {}

    public static UUID pipeline(String repository, String workflow) {
        return named("urn:pipesor:v1:pipeline:github:" + external(repository) + ":" + external(workflow));
    }

    public static UUID execution(String repository, String run, int attempt) {
        if (attempt < 1) { throw new NormalizationFailure(INVALID_IDENTITY); }
        return named("urn:pipesor:v1:execution:github:" + external(repository) + ":" + external(run) + ":" + attempt);
    }

    public static UUID incident(UUID execution) {
        return named("urn:pipesor:v1:incident:" + required(execution));
    }

    public static UUID evidence(UUID message) {
        return named("urn:pipesor:v1:evidence:" + required(message));
    }

    public static String external(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) { throw new NormalizationFailure(INVALID_IDENTITY); }
        return value;
    }

    public static UUID required(UUID value) {
        if (value == null || value.equals(new UUID(0, 0))) { throw new NormalizationFailure(INVALID_IDENTITY); }
        return value;
    }

    private static UUID named(String name) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(URL_NAMESPACE.getMostSignificantBits())
                    .putLong(URL_NAMESPACE.getLeastSignificantBits()).array());
            var bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            var buffer = ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-1 unavailable"); }
    }
}
