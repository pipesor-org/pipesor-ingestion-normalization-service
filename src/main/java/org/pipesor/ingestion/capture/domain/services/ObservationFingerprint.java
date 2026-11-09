package org.pipesor.ingestion.capture.domain.services;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;

/** Version 1: UTF-8 fields prefixed by a big-endian int byte length; -1 marks null. */
public final class ObservationFingerprint {
    private ObservationFingerprint() {}
    public record Fingerprint(String observationKey, String protectedContentDigest) {}
    public static Fingerprint of(CaptureScope scope, WorkflowObservation value, String fragmentLocator) {
        var content = new Canonical();
        content.fields("protected-content-v1", value.commitSha(), value.service(), value.environment());
        content.time(value.occurredAt());
        var execution = value.execution();
        content.fields(execution.branch(), execution.status(), execution.conclusion());
        content.time(execution.startedAt()); content.time(execution.completedAt());
        content.fields(Integer.toString(value.stages().size()));
        for (var stage : value.stages()) {
            content.fields(stage.stage().name(), stage.status()); content.time(stage.startedAt()); content.time(stage.completedAt());
        }
        var failure = value.failure();
        content.fields(failure == null ? null : "failure");
        if (failure != null) { content.fields(failure.stage().name(), failure.summary()); content.time(failure.detectedAt()); }
        var evidence = value.evidence();
        content.fields(evidence == null ? null : "evidence");
        if (evidence != null) { content.fields(evidence.stage().name(), evidence.job(), evidence.excerpt(),
                evidence.sequence() == null ? null : evidence.sequence().toString(), fragmentLocator); }
        var digest = hash(content.bytes());
        var identity = new Canonical();
        identity.fields("observation-key-v1", scope.captureDomain(), scope.tenantId().toString(), "github_actions",
                scope.appId(), scope.accountId(), scope.installationId(), scope.repositoryId(), scope.workflowId(),
                execution.githubRunId(), Integer.toString(execution.runAttempt()));
        identity.time(value.sourceUpdatedAt()); identity.fields(digest);
        return new Fingerprint(hash(identity.bytes()), digest);
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static final class Canonical {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream data = new DataOutputStream(buffer);
        void fields(String... fields) {
            try {
                for (var field : fields) {
                    if (field == null) { data.writeInt(-1); }
                    else { var bytes = field.getBytes(StandardCharsets.UTF_8); data.writeInt(bytes.length); data.write(bytes); }
                }
            } catch (IOException impossible) { throw new AssertionError(impossible); }
        }
        void time(Instant value) {
            if (value == null) { fields((String) null); }
            else { fields(Long.toString(value.getEpochSecond()), Integer.toString(value.getNano())); }
        }
        byte[] bytes() { return buffer.toByteArray(); }
    }
}
