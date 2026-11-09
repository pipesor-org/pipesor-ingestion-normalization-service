package org.pipesor.ingestion.capture.domain.services;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.WorkflowObservation;
import static org.junit.jupiter.api.Assertions.*;

class ObservationFingerprintTests {
    private static final UUID TENANT = UUID.fromString("41e95213-69ea-4bca-9be7-eac6495d3b19");
    private static final Instant TIME = Instant.parse("2026-11-08T12:00:00.000000001Z");
    private CaptureScope scope(String revision) { return new CaptureScope("machine", TENANT, "1", "2", "3", "4", "5", revision); }
    private WorkflowObservation observation(CaptureScope scope, String correlation, String service, String environment, Instant observed, Instant updated) {
        return new WorkflowObservation(scope.context(), scope.pipeline(), new WorkflowObservation.Execution("6", 1, "main", "completed", "success", null, updated),
                "a".repeat(40), service, environment, correlation, observed, updated, List.of(), null, null);
    }
    @Test void correlationAndScopeRevisionDoNotCreateNewObservation() {
        var first = scope("scope-1"); var retry = scope("scope-2");
        assertEquals(ObservationFingerprint.of(first, observation(first, "first-correlation", "service", "environment", TIME, TIME), null),
                ObservationFingerprint.of(retry, observation(retry, "retry-correlation", "service", "environment", TIME, TIME), null));
    }
    @Test void canonicalBoundariesAndNullMarkersAvoidAmbiguousContent() {
        var scope = scope("scope-1");
        var first = ObservationFingerprint.of(scope, observation(scope, "corr", "a:b", "c", TIME, TIME), null);
        var other = ObservationFingerprint.of(scope, observation(scope, "corr", "a", "b:c", TIME, TIME), null);
        assertNotEquals(first, other);
        assertNotEquals(ObservationFingerprint.of(scope, observation(scope, "corr", null, "", TIME, TIME), null),
                ObservationFingerprint.of(scope, observation(scope, "corr", "", null, TIME, TIME), null));
    }
    @Test void sourceNanosecondsAndOriginalOccurrenceRemainPartOfIdentity() {
        var scope = scope("scope-1"); var first = ObservationFingerprint.of(scope, observation(scope, "corr", null, null, TIME, TIME), null);
        assertNotEquals(first, ObservationFingerprint.of(scope, observation(scope, "corr", null, null, TIME.plusNanos(1), TIME), null));
        assertNotEquals(first, ObservationFingerprint.of(scope, observation(scope, "corr", null, null, TIME, TIME.plusNanos(1)), null));
    }
}
