package org.pipesor.ingestion.normalization;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.commands.NormalizeObservationCommand;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.LogicalIds;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure.Reason;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation.*;
import org.pipesor.ingestion.normalization.interfaces.messaging.transform.NormalizedMessagesTransformer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.pipesor.ingestion.normalization.NormalizationFixtures.*;
import static org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure.Reason.*;

class NormalizationTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final NormalizedMessagesTransformer transformer = new NormalizedMessagesTransformer();

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void matchesFixturesForActualPipelineShapeAndEvidenceV2(int attempt) throws Exception {
        var messages = messages(attempt);
        assertEquals(2, messages.size());
        for (int i = 0; i < 2; i++) {
            var kind = i == 0 ? "snapshot" : "evidence";
            var expected = getClass().getResourceAsStream("/normalization/" + kind + "-attempt-" + attempt + ".json");
            assertNotNull(expected);
            try (expected) { assertEquals(json.readTree(expected), json.readTree(messages.get(i).body())); }
            var message = messages.get(i);
            var body = json.readTree(message.body());
            assertEquals(message.messageId().toString(), body.get(i == 0 ? "eventId" : "message_id").asString());
            assertEquals(message.correlationId(), body.get(i == 0 ? "correlationId" : "correlation_id").asString());
            assertEquals(i == 0 ? "execution.snapshot" : "protected_workflow_evidence", message.applicationProperties().get("eventType"));
            assertEquals(i == 0 ? 1 : 2, message.applicationProperties().get("schemaMajor"));
            assertEquals("application/json; charset=utf-8", message.contentType());
            if (i == 1) { assertEquals(4294967303L, body.get("evidence").get("sequence").asLong()); }
            assertFalse(new String(message.body(), StandardCharsets.UTF_8).contains(TOKEN));
        }
        assertNotEquals(messages.get(0).messageId(), messages.get(1).messageId());
    }

    @Test
    void usesExactOwnerUuidV5NamesAndDistinctAttempts() throws Exception {
        var expected = json.readTree(getClass().getResourceAsStream("/normalization/logical-identities.json"));
        var handler = new NormalizeObservationHandler(POLICY);
        for (int attempt : List.of(1, 2)) {
            var result = handler.handle(command(attempt));
            var item = expected.get("attempt" + attempt);
            assertEquals(item.get("pipeline").asString(), result.pipelineId().toString());
            assertEquals(item.get("execution").asString(), result.executionId().toString());
            assertEquals(item.get("incident").asString(), result.incidentId().toString());
            assertEquals(item.get("evidence").asString(), result.evidenceId().toString());
            assertEquals(5, result.executionId().version());
        }
        assertNotEquals(handler.handle(command(1)).executionId(), handler.handle(command(2)).executionId());
        assertNotEquals(handler.handle(command(1)).incidentId(), handler.handle(command(2)).incidentId());
    }

    @Test
    void retryPreservesIdsTimestampsAndBytesEvenWhenStageInputOrderChanges() {
        var source = observation(1);
        var reversed = new ArrayList<>(source.stages());
        java.util.Collections.reverse(reversed);
        var reordered = copy(source, source.execution(), source.commitSha(), source.service(), source.environment(), reversed, source.failure(), source.evidence());
        var handler = new NormalizeObservationHandler(POLICY);
        var original = transformer.toMessages(handler.handle(command(source, 1)));
        var retry = transformer.toMessages(handler.handle(command(reordered, 1)));
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(original.get(i).body(), retry.get(i).body());
            assertEquals(original.get(i).messageId(), retry.get(i).messageId());
        }
        var result = handler.handle(command(1));
        assertEquals(OCCURRED, result.observation().occurredAt());
        assertEquals(UPDATED, result.observation().sourceUpdatedAt());
        assertNotEquals(OCCURRED, UPDATED);
    }

    @Test
    void protectsEveryPublishableTextBeforeSerializationAndPreservesNullableContext() {
        var source = observation(1);
        var calls = new AtomicInteger();
        var handler = new NormalizeObservationHandler(texts -> {
            calls.incrementAndGet();
            assertTrue(texts.branch().contains(TOKEN));
            assertTrue(texts.failureSummary().contains(TOKEN));
            assertTrue(texts.job().contains(TOKEN));
            assertTrue(texts.excerpt().contains(TOKEN));
            assertEquals(source.correlationId(), texts.correlationId());
            assertEquals(source.captureContext().scopeRevision(), texts.scopeRevision());
            return POLICY.protect(texts);
        });
        var result = handler.handle(command(1));
        assertEquals(1, calls.get());
        assertEquals("feature/[redacted]", result.observation().execution().branch());
        assertNull(result.observation().service());
        assertNull(result.observation().environment());
        assertEquals(source.execution().runAttempt(), result.observation().execution().runAttempt());
        assertEquals(source.captureContext(), result.observation().captureContext());
        transformer.toMessages(result);
        assertEquals(1, calls.get());
        var contexts = copy(source, source.execution(), source.commitSha(), "service-" + TOKEN, "env-" + TOKEN,
                source.stages(), source.failure(), source.evidence());
        var protectedContexts = handler.handle(command(contexts, 1)).observation();
        assertEquals("service-[redacted]", protectedContexts.service());
        assertEquals("env-[redacted]", protectedContexts.environment());
    }

    @Test
    void policyRefusalProducesNoMessagesOrLeakedDiagnostics() {
        var output = new AtomicReference<List<NormalizedMessagesTransformer.Message>>();
        var failure = expect(PROTECTION_REFUSED, () -> output.set(transformer.toMessages(
                new NormalizeObservationHandler(text -> Optional.empty()).handle(command(1)))));
        assertNull(output.get());
        assertSafe(failure);
    }

    @Test
    void dependencyFailureDiscardsSensitiveMessageAndCauseAndProducesNoMessages() {
        var output = new AtomicReference<List<NormalizedMessagesTransformer.Message>>();
        var failure = expect(PROTECTION_UNAVAILABLE, () -> output.set(transformer.toMessages(
                new NormalizeObservationHandler(text -> { throw new IllegalStateException(TOKEN, new RuntimeException(TOKEN)); }).handle(command(1)))));
        assertNull(output.get());
        assertSafe(failure);
    }

    @Test
    void rejectsMissingProtectionResultEmptyProtectedExcerptAndContextRewriting() {
        expect(INVALID_PROTECTION_RESULT, () -> new NormalizeObservationHandler(text -> null).handle(command(1)));
        expect(INVALID_PROTECTION_RESULT, () -> new NormalizeObservationHandler(text -> Optional.of(new EvidenceProtection.Texts(
                text.branch(), text.service(), text.environment(), text.failureSummary(), text.job(), " ", text.correlationId(), text.scopeRevision()))).handle(command(1)));
        expect(INVALID_PROTECTION_RESULT, () -> new NormalizeObservationHandler(text -> Optional.of(new EvidenceProtection.Texts(
                text.branch(), text.service(), text.environment(), text.failureSummary(), text.job(), text.excerpt(), "changed", text.scopeRevision()))).handle(command(1)));
        expect(INVALID_PROTECTION_RESULT, () -> new NormalizeObservationHandler(text -> Optional.of(new EvidenceProtection.Texts(
                text.branch(), text.service(), text.environment(), text.failureSummary(), text.job(), text.excerpt(), text.correlationId(), "changed"))).handle(command(1)));
        expect(INVALID_PROTECTION_RESULT, () -> new NormalizeObservationHandler(text -> Optional.of(new EvidenceProtection.Texts(
                text.branch(), text.service(), text.environment(), text.failureSummary(), " ", text.excerpt(), text.correlationId(), text.scopeRevision()))).handle(command(1)));
    }

    @Test
    void rejectsCrossBoundTenantAccountInstallationRevisionRepositoryAndWorkflowBeforePolicy() {
        var calls = new AtomicInteger();
        var handler = new NormalizeObservationHandler(text -> { calls.incrementAndGet(); return POLICY.protect(text); });
        var source = observation(1);
        var contexts = List.of(new CaptureContext(UUID.fromString("00000000-0000-4000-8000-000000000001"), CAPTURE.githubAccountId(), CAPTURE.githubInstallationId(), CAPTURE.scopeRevision()),
                new CaptureContext(CAPTURE.tenantId(), "1", CAPTURE.githubInstallationId(), CAPTURE.scopeRevision()),
                new CaptureContext(CAPTURE.tenantId(), CAPTURE.githubAccountId(), "1", CAPTURE.scopeRevision()),
                new CaptureContext(CAPTURE.tenantId(), CAPTURE.githubAccountId(), CAPTURE.githubInstallationId(), "other-revision"));
        for (var context : contexts) {
            expect(SCOPE_MISMATCH, () -> handler.handle(new NormalizeObservationCommand(source, context, PIPELINE,
                    command(1).snapshotMessageId(), command(1).evidenceMessageId())));
        }
        for (var pipeline : List.of(new Pipeline("1", WORKFLOW), new Pipeline(REPOSITORY, "1"))) {
            expect(SCOPE_MISMATCH, () -> handler.handle(new NormalizeObservationCommand(source, CAPTURE, pipeline,
                    command(1).snapshotMessageId(), command(1).evidenceMessageId())));
        }
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "01", "-1", "1.5", "1e3", " 1", "1 ", "synthetic-secret-do-not-echo"})
    void rejectsNoncanonicalExternalIdsWithSafeExactCause(String invalid) {
        assertSafe(expect(INVALID_IDENTITY, () -> new Pipeline(invalid, WORKFLOW)));
        expect(INVALID_IDENTITY, () -> new Execution(invalid, 1, "main", "queued", null, null, null));
        expect(INVALID_IDENTITY, () -> new CaptureContext(CAPTURE.tenantId(), invalid, "1", "rev"));
        expect(INVALID_IDENTITY, () -> new CaptureContext(CAPTURE.tenantId(), "1", invalid, "rev"));
    }

    @Test
    void rejectsInvalidAttemptsAndMissingOrReusedMessageIdentity() {
        expect(INVALID_IDENTITY, () -> LogicalIds.execution(REPOSITORY, RUN, 0));
        expect(INVALID_IDENTITY, () -> new Execution(RUN, 0, "main", "queued", null, null, null));
        expect(INVALID_IDENTITY, () -> new NormalizeObservationCommand(observation(1), CAPTURE, PIPELINE, null, command(1).evidenceMessageId()));
        expect(INVALID_IDENTITY, () -> new NormalizeObservationCommand(observation(1), CAPTURE, PIPELINE, command(1).snapshotMessageId(), null));
        expect(INVALID_IDENTITY, () -> new NormalizeObservationCommand(observation(1), CAPTURE, PIPELINE, command(1).snapshotMessageId(), command(1).snapshotMessageId()));
        expect(INVALID_IDENTITY, () -> new CaptureContext(new UUID(0, 0), "1", "1", "rev"));
    }

    @Test
    void rejectsInvalidStatusConclusionChronologyAndMissingBranch() {
        expect(INVALID_STATUS, () -> new Execution(RUN, 1, "main", TOKEN, null, null, null));
        expect(INVALID_STATUS, () -> new Execution(RUN, 1, "main", "completed", null, null, null));
        expect(INVALID_STATUS, () -> new Execution(RUN, 1, "main", "completed", TOKEN, null, null));
        expect(INVALID_STATUS, () -> new Execution(RUN, 1, "main", "queued", "failure", null, null));
        expect(INVALID_STATUS, () -> new Execution(RUN, 1, "main", "queued", null, null, END));
        expect(INVALID_TIME, () -> new Execution(RUN, 1, "main", "completed", "failure", END, START));
        expect(INVALID_CONTEXT, () -> new Execution(RUN, 1, null, "completed", "failure", START, END));
    }

    @Test
    void rejectsUnclassifiedDuplicateAndMismatchedStageWithoutBuildDefault() {
        expect(INVALID_STAGE, () -> new Stage(null, "completed", START, END));
        expect(INVALID_STAGE, () -> new Stage(StageName.TESTING, TOKEN, START, END));
        expect(INVALID_STAGE, () -> new Failure(null, "summary", DETECTED));
        expect(INVALID_STAGE, () -> new Evidence(null, "job", "excerpt", null));
        var source = observation(1);
        expect(INVALID_STAGE, () -> copy(source, source.execution(), SHA, null, null,
                List.of(source.stages().getFirst(), source.stages().getFirst()), source.failure(), source.evidence()));
        expect(INVALID_EVIDENCE, () -> copy(source, source.execution(), SHA, null, null, source.stages(), source.failure(),
                new Evidence(StageName.BUILD, "job", "excerpt", null)));
        assertEquals(StageName.INFRASTRUCTURE, new NormalizeObservationHandler(POLICY).handle(command(1)).observation().failure().stage());
    }

    @Test
    void evidenceRequiresTerminalFailedAttemptAndNonemptyExcerpt() {
        var source = observation(1);
        var success = new Execution(RUN, 1, "main", "completed", "success", START, END);
        expect(INVALID_EVIDENCE, () -> copy(source, success, SHA, null, null, source.stages(), source.failure(), source.evidence()));
        expect(INVALID_EVIDENCE, () -> copy(source, success, SHA, null, null, source.stages(), null, source.evidence()));
        expect(INVALID_EVIDENCE, () -> copy(source, source.execution(), SHA, null, null, source.stages(), null, null));
        expect(INVALID_EVIDENCE, () -> copy(source, source.execution(), SHA, null, null, source.stages(), source.failure(), null));
        expect(INVALID_EVIDENCE, () -> new Evidence(StageName.INFRASTRUCTURE, "job", " ", null));
        expect(INVALID_EVIDENCE, () -> new Evidence(StageName.INFRASTRUCTURE, "job", "excerpt", -1L));
        expect(INVALID_EVIDENCE, () -> new Evidence(StageName.INFRASTRUCTURE, " ", "excerpt", null));
    }

    @Test
    void successfulOrUnfinishedSnapshotsRemainCompleteWithoutInventedEvidenceOrIncident() {
        var source = observation(1);
        for (var execution : List.of(new Execution(RUN, 1, "main", "completed", "success", START, END),
                new Execution(RUN, 1, "main", "in_progress", null, START, null))) {
            var observation = copy(source, execution, SHA, null, null, source.stages(), null, null);
            var result = new NormalizeObservationHandler(POLICY).handle(command(observation, 1));
            assertEquals(1, transformer.toMessages(result).size());
            assertNull(result.incidentId());
            assertNull(result.evidenceId());
            assertEquals(execution.status(), result.observation().execution().status());
        }
    }

    @Test
    void supportsFull40And64CharacterCommitShaWithoutGuessingMissingData() {
        var source = observation(1);
        for (var sha : List.of(SHA, SHA.substring(0, 40))) {
            var result = new NormalizeObservationHandler(POLICY).handle(command(copy(source, source.execution(), sha,
                    null, null, source.stages(), source.failure(), source.evidence()), 1));
            assertEquals(sha.toLowerCase(java.util.Locale.ROOT), result.observation().commitSha());
        }
        expect(INVALID_IDENTITY, () -> copy(source, source.execution(), "abc123", null, null, source.stages(), source.failure(), source.evidence()));
        expect(INVALID_IDENTITY, () -> copy(source, source.execution(), null, null, null, source.stages(), source.failure(), source.evidence()));
        expect(INVALID_CONTEXT, () -> new WorkflowObservation(CAPTURE, PIPELINE, source.execution(), SHA, null, null,
                "bad correlation " + TOKEN, OCCURRED, UPDATED, source.stages(), source.failure(), source.evidence()));
        expect(INVALID_TIME, () -> new WorkflowObservation(CAPTURE, PIPELINE, source.execution(), SHA, null, null,
                source.correlationId(), null, UPDATED, source.stages(), source.failure(), source.evidence()));
    }

    @Test
    void immutableObservationsAndTransportCannotChangeRetryContent() {
        var source = observation(1);
        var stages = new ArrayList<>(source.stages());
        var observation = copy(source, source.execution(), SHA, null, null, stages, source.failure(), source.evidence());
        stages.clear();
        assertEquals(2, observation.stages().size());
        assertThrowsExactly(UnsupportedOperationException.class, () -> observation.stages().clear());
        var message = messages(1).getFirst();
        var bytes = message.body();
        bytes[0] = 0;
        assertEquals('{', message.body()[0]);
        assertThrowsExactly(UnsupportedOperationException.class, () -> message.applicationProperties().put("eventType", "other"));
        for (var value : List.of(source, source.execution(), source.failure(), source.evidence(), command(1),
                new EvidenceProtection.Texts(TOKEN, TOKEN, TOKEN, TOKEN, TOKEN, TOKEN, TOKEN, TOKEN), message)) {
            assertFalse(value.toString().contains(TOKEN));
        }
    }

    @Test
    void theRealProtectionPortFeedsBothMessages() {
        assertEquals(2, new NormalizedMessagesTransformer().toMessages(new NormalizeObservationHandler(POLICY).handle(command(1))).size());
    }

    private static WorkflowObservation copy(WorkflowObservation source, Execution execution, String sha, String service,
            String environment, List<Stage> stages, Failure failure, Evidence evidence) {
        return new WorkflowObservation(source.captureContext(), source.pipeline(), execution, sha, service, environment,
                source.correlationId(), source.occurredAt(), source.sourceUpdatedAt(), stages, failure, evidence);
    }

    private static NormalizationFailure expect(Reason reason, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrowsExactly(NormalizationFailure.class, action);
        assertEquals(reason, failure.reason());
        return failure;
    }

    private static void assertSafe(NormalizationFailure failure) {
        assertNull(failure.getCause());
        assertEquals(failure.reason().name(), failure.getMessage());
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(TOKEN));
    }
}
