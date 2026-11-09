package org.pipesor.ingestion.normalization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.commands.NormalizeObservationCommand;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.*;
import org.pipesor.ingestion.normalization.interfaces.messaging.transform.NormalizedMessagesTransformer;

/** Synthetic-only fixture policy; never registered in application configuration. */
public final class NormalizationFixtures {
    public static final String TOKEN = "synthetic-secret-do-not-echo";
    public static final String REPOSITORY = "92233720368547758081234567";
    public static final String WORKFLOW = "184467440737095516170";
    public static final String RUN = "999999999999999999999999999";
    public static final String SHA = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789";
    public static final Instant OCCURRED = Instant.parse("2026-11-08T09:20:31.123456789Z");
    public static final Instant UPDATED = Instant.parse("2026-11-08T09:20:40.987654321Z");
    public static final Instant START = Instant.parse("2026-11-08T09:00:00Z");
    public static final Instant END = Instant.parse("2026-11-08T09:20:00Z");
    public static final Instant DETECTED = Instant.parse("2026-11-08T09:19:59Z");
    public static final CaptureContext CAPTURE = new CaptureContext(UUID.fromString("d91d1df6-ae89-40c2-99b5-20edfb3e9ed0"),
            "6802100000000000000000000", "7710600000000000000000000", "scope-rev-12");
    public static final Pipeline PIPELINE = new Pipeline(REPOSITORY, WORKFLOW);
    public static final EvidenceProtection POLICY = source -> Optional.of(new EvidenceProtection.Texts(
            mask(source.branch()), mask(source.service()), mask(source.environment()), mask(source.failureSummary()),
            mask(source.job()), mask(source.excerpt()), source.correlationId(), source.scopeRevision()));

    private NormalizationFixtures() {}

    public static WorkflowObservation observation(int attempt) {
        return new WorkflowObservation(CAPTURE, PIPELINE,
                new Execution(RUN, attempt, "feature/" + TOKEN, "completed", attempt == 1 ? "failure" : "timed_out", START, END),
                attempt == 1 ? SHA.substring(0, 40) : SHA, null, null, "ci-investigation-204", OCCURRED, UPDATED,
                List.of(new Stage(StageName.INFRASTRUCTURE, "completed", START, END),
                        new Stage(StageName.TESTING, "completed", START, END)),
                new Failure(StageName.INFRASTRUCTURE, "Failure " + TOKEN, DETECTED),
                new Evidence(StageName.INFRASTRUCTURE, "job-" + TOKEN, "Trace " + TOKEN, 4294967303L));
    }

    public static NormalizeObservationCommand command(int attempt) { return command(observation(attempt), attempt); }

    public static NormalizeObservationCommand command(WorkflowObservation observation, int attempt) {
        return new NormalizeObservationCommand(observation, CAPTURE, PIPELINE,
                UUID.fromString("41be428e-b689-4f00-9277-00000000000" + attempt),
                observation.evidence() == null ? null : UUID.fromString("cf6b69cf-a28d-49f3-bceb-00000000000" + attempt));
    }

    public static List<NormalizedMessagesTransformer.Message> messages(int attempt) {
        return new NormalizedMessagesTransformer().toMessages(new NormalizeObservationHandler(POLICY).handle(command(attempt)));
    }

    private static String mask(String value) { return value == null ? null : value.replace(TOKEN, "[redacted]"); }
}
