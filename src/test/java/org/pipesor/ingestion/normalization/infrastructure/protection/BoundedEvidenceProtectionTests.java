package org.pipesor.ingestion.normalization.infrastructure.protection;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.normalization.NormalizationFixtures;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.commands.NormalizeObservationCommand;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection.Texts;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import org.pipesor.ingestion.normalization.interfaces.messaging.transform.NormalizedMessagesTransformer;
import static org.junit.jupiter.api.Assertions.*;

class BoundedEvidenceProtectionTests {
    private final BoundedEvidenceProtection policy = new BoundedEvidenceProtection("known-patterns-v1", 65536, 65536, 100);

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"password\" : \"hidden value with spaces\"}",
            "{\"pass\\u0077ord\":\"hidden\\\"escaped value\"}",
            "password='hidden value with spaces'",
            "DB_PASSWORD = hidden value with spaces",
            "DB_PASSWORD=hiddenFirst,hiddenSuffix",
            "password=hiddenFirst;hiddenSuffix",
            "password=hiddenFirst}hiddenSuffix",
            "{\"access_token\":\"hidden\\nmultiline value\"}",
            "Authorization: Bearer hidden-credential",
            "Bearer hidden-credential",
            "Bearer hidden~credential+/==",
            "prefix/ghp_hiddenCredential",
            "github_pat_hiddenCredential",
            "gho_hiddenCredential ghu_hiddenCredential ghs_hiddenCredential ghr_hiddenCredential",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJoaWRkZW4ifQ.hiddenSignature",
            "eyAiYWxnIjogIkhTMjU2In0.eyJzdWIiOiJoaWRkZW4ifQ.hiddenSignature",
            "person.hidden@example.invalid",
            "192.168.10.27", "host=192.168.10.27:443.", "2001:db8::42", "host:2001:db8::42", "[::ffff:192.168.10.27]", "[fe80::1234%eth0]"
    })
    void protectsKnownVariationsInTheHandlerAndSerializedMessages(String sensitive) {
        var observation = observation(sensitive, sensitive, sensitive, sensitive, sensitive, sensitive, "corr-204", "scope-12");
        var result = new NormalizeObservationHandler(policy).handle(command(observation));
        var protectedObservation = result.observation();
        for (var text : new String[] {protectedObservation.execution().branch(), protectedObservation.service(),
                protectedObservation.environment(), protectedObservation.failure().summary(),
                protectedObservation.evidence().job(), protectedObservation.evidence().excerpt()}) {
            assertTrue(text.contains("[redacted]"));
            assertFalse(text.contains("hidden"));
            assertFalse(text.contains("192.168.10.27"));
            assertFalse(text.contains("2001:db8::42"));
        }
        var messages = new NormalizedMessagesTransformer().toMessages(result);
        assertEquals(2, messages.size());
        for (var message : messages) {
            var body = new String(message.body(), StandardCharsets.UTF_8);
            assertFalse(body.contains("hidden"));
            assertTrue(body.contains("corr-204"));
        }
        assertEquals("corr-204", protectedObservation.correlationId());
        assertEquals("scope-12", protectedObservation.captureContext().scopeRevision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-----BEGIN RSA PRIVATE KEY-----\nsynthetic\n-----END RSA PRIVATE KEY-----",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic", "line\u0000hidden", "line\u001b[31mhidden", "pass\u200Dword=hidden",
            "credentials=[\"firstCredential\",\"secondCredential\"]",
            "credentials={\"user\":\"firstCredential\",\"other\":\"secondCredential\"}",
            "{\"context\":{\"credentials\":[{\"value\":\"nestedCredential\"}]}}",
            "password=\"unterminated", "{\"message\":\"escaped \\u0067hp_hidden\"}",
            "{\"message\":\"nested \\\"password\\\":\\\"hidden\\\"\"}"})
    void refusesUnsafeOrUnsupportedContentThroughTheHandlerWithoutLeakingCauses(String sensitive) {
        var command = command(observation("main", null, null, "failed", "job", sensitive, "corr", "scope"));
        var failure = assertThrows(NormalizationFailure.class, () -> new NormalizeObservationHandler(policy).handle(command));
        assertEquals(NormalizationFailure.Reason.PROTECTION_REFUSED, failure.reason());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("hidden"));
        assertFalse(failure.toString().contains("synthetic"));
        assertFalse(failure.toString().contains("Credential"));
        // Refusal stops before a result exists for the messaging transformer.
        assertTrue(policy.protect(texts(sensitive)).isEmpty());
    }

    @Test
    void minimizesKnownIdentifiersPreservesSafeMultilineUnicodeAndNeverTruncatesBeforeFiltering() {
        var source = texts("  line α😀\ncontact person@example.invalid at 10.2.3.4\npassword=\"long secret\"  ");
        var output = policy.protect(source).orElseThrow();
        assertEquals("line α😀\ncontact [redacted] at [redacted]\n[redacted]", output.excerpt());
        assertEquals(source.correlationId(), output.correlationId());
        assertEquals(source.scopeRevision(), output.scopeRevision());
        assertTrue(new BoundedEvidenceProtection("v1", 65536, 10, 10).protect(source).isEmpty());
    }

    @Test
    void inputLimitCountsUtf8AcrossAllFieldsAndOutputLimitCountsUtf16AfterProtection() {
        var source = texts("😀é");
        int bytes = all(source).getBytes(StandardCharsets.UTF_8).length;
        int chars = all(source).length();
        assertTrue(new BoundedEvidenceProtection("v1", bytes, chars, 1).protect(source).isPresent());
        assertTrue(new BoundedEvidenceProtection("v1", bytes - 1, chars, 1).protect(source).isEmpty());
        assertTrue(new BoundedEvidenceProtection("v1", bytes, chars - 1, 1).protect(source).isEmpty());
        assertTrue(policy.protect(texts("\uD800")).isEmpty());
        assertTrue(policy.protect(texts("\uDC00")).isEmpty());
    }

    @Test
    void exhaustsTheRedactionBudgetWithoutReturningPartialEvidence() {
        assertTrue(new BoundedEvidenceProtection("v1", 1000, 1000, 1)
                .protect(texts("Bearer hidden1\nperson@example.invalid")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghp_hidden", "192.168.0.1", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJoaWRkZW4ifQ.signature", "password:hidden"})
    void refusesSensitiveImmutableMetadataInsteadOfChangingIt(String identity) {
        var command = command(observation("main", null, null, "failure", "job", "trace", identity, "scope"));
        var failure = assertThrows(NormalizationFailure.class, () -> new NormalizeObservationHandler(policy).handle(command));
        assertEquals(NormalizationFailure.Reason.PROTECTION_REFUSED, failure.reason());
        var scopeCommand = command(observation("main", null, null, "failure", "job", "trace", "corr", identity));
        assertEquals(NormalizationFailure.Reason.PROTECTION_REFUSED,
                assertThrows(NormalizationFailure.class, () -> new NormalizeObservationHandler(policy).handle(scopeCommand)).reason());
    }

    @Test
    void refusesBlankRequiredAndOptionalValuesAndMissingIdentity() {
        assertTrue(policy.protect(texts(" \n ")).isEmpty());
        assertTrue(policy.protect(new Texts(" ", null, null, null, null, null, "corr", "scope")).isEmpty());
        assertTrue(policy.protect(new Texts("main", " ", null, null, null, null, "corr", "scope")).isEmpty());
        assertTrue(policy.protect(new Texts("main", null, null, null, null, null, null, "scope")).isEmpty());
        assertTrue(policy.protect(null).isEmpty());
    }

    @Test
    void exposesOnlyImmutablePolicyMetadataAndBoundsAdversarialScanning() {
        assertEquals("known-patterns-v1", policy.policyVersion());
        assertEquals(65536, policy.maxInputBytes());
        assertEquals(65536, policy.maxOutputChars());
        assertEquals(100, policy.maxRedactions());
        assertEquals("BoundedEvidenceProtection[configured]", policy.toString());
        assertEquals("Texts[redacted]", texts("hidden").toString());
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertTrue(policy.protect(texts("\"a\" ".repeat(10000))).isPresent());
            assertTrue(policy.protect(texts("a".repeat(60000))).isPresent());
            assertTrue(policy.protect(texts("\\\"a".repeat(18000))).isEmpty());
        });
    }

    private static NormalizeObservationCommand command(WorkflowObservation observation) {
        var fixture = NormalizationFixtures.command(1);
        return new NormalizeObservationCommand(observation, observation.captureContext(), observation.pipeline(), fixture.snapshotMessageId(), fixture.evidenceMessageId());
    }

    private static Texts texts(String excerpt) { return new Texts("main", null, null, null, null, excerpt, "corr", "scope"); }
    private static String all(Texts value) { return value.branch() + value.excerpt() + value.correlationId() + value.scopeRevision(); }

    private static WorkflowObservation observation(String branch, String service, String environment, String summary,
            String job, String excerpt, String correlation, String revision) {
        var base = NormalizationFixtures.observation(1);
        var capture = new WorkflowObservation.CaptureContext(base.captureContext().tenantId(), base.captureContext().githubAccountId(),
                base.captureContext().githubInstallationId(), revision);
        return new WorkflowObservation(capture, base.pipeline(),
                new WorkflowObservation.Execution(base.execution().githubRunId(), 1, branch, "completed", "failure",
                        base.execution().startedAt(), base.execution().completedAt()),
                base.commitSha(), service, environment, correlation, base.occurredAt(), base.sourceUpdatedAt(), base.stages(),
                new WorkflowObservation.Failure(base.failure().stage(), summary, base.failure().detectedAt()),
                new WorkflowObservation.Evidence(base.evidence().stage(), job, excerpt, base.evidence().sequence()));
    }
}
