package org.pipesor.ingestion.capture.application.commandhandlers;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.pipesor.ingestion.capture.application.commands.CaptureCommands.Claim;
import org.pipesor.ingestion.capture.application.ports.PreparationReadyReceipts;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.normalization.domain.model.valueobjects.NormalizationFailure;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparationWorkerTests {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final Instant LEASE = NOW.plusSeconds(30), RECOVER = NOW.plusSeconds(120);
    private final UUID owner = UUID.randomUUID();
    private final PrepareGitHubCapture handler = mock(PrepareGitHubCapture.class);
    private final MutableClock clock = new MutableClock();
    private static UUID id(int n) { return new UUID(0, n); }
    private PreparationWorker worker(PreparationReadyReceipts ready, int batch) {
        return new PreparationWorker(ready, handler, clock, batch, Duration.ofSeconds(10));
    }
    static final class MutableClock extends Clock {
        Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void forwardsExactHorizonsAndContinuesAfterMaterialFailures() {
        var ids = List.of(id(1), id(2), id(3), id(4), id(5), id(6));
        when(handler.prepare(any(), eq(RECOVER))).thenAnswer(invocation -> {
            Claim claim = invocation.getArgument(0);
            assertEquals(owner, claim.owner()); assertEquals(LEASE, claim.leaseUntil());
            return switch ((int) claim.receiptId().getLeastSignificantBits()) {
                case 1 -> throw new CaptureFailure(CaptureFailure.Reason.INVALID_INPUT);
                case 2 -> throw new NormalizationFailure(NormalizationFailure.Reason.PROTECTION_REFUSED);
                case 3 -> throw new CaptureFailure(CaptureFailure.Reason.SCOPE_CHANGED);
                case 4 -> throw new CaptureFailure(CaptureFailure.Reason.STALE_CLAIM);
                case 5 -> throw new IllegalStateException("private synthetic provider details");
                default -> null;
            };
        });
        var result = worker((now, limit, after) -> ids, 6).tick(owner, LEASE, RECOVER);
        assertEquals(new PreparationWorker.Result(6, 1, 1, 1, 1, 1, 1, false, false, false), result);
        assertFalse(result.toString().contains("private"));
    }
    @Test void cursorPassesRepeatedFailuresAndWrapsOnce() {
        var seen = new ArrayList<UUID>();
        PreparationReadyReceipts ready = (now, limit, after) -> {
            seen.add(after);
            return List.of(id(1), id(2)).stream().filter(id -> after == null || id.compareTo(after) > 0).limit(limit).toList();
        };
        when(handler.prepare(any(), any())).thenThrow(new CaptureFailure(CaptureFailure.Reason.UNAVAILABLE));
        var operation = worker(ready, 1);
        operation.tick(owner, LEASE, RECOVER); operation.tick(owner, LEASE, RECOVER); operation.tick(owner, LEASE, RECOVER);
        assertEquals(Arrays.asList(null, id(1), id(2), null), seen);
        var claims = org.mockito.ArgumentCaptor.forClass(Claim.class);
        verify(handler, times(3)).prepare(claims.capture(), eq(RECOVER));
        assertEquals(List.of(id(1), id(2), id(1)), claims.getAllValues().stream().map(Claim::receiptId).toList());
    }
    @Test void malformedDiscoveryOrProviderFailureNeverLooksLikeAnEmptySuccessfulPass() {
        var candidates = Arrays.<List<UUID>>asList(null, List.of(id(1), id(1)), List.of(id(1), id(2)), Arrays.asList(id(1), null));
        for (var values : candidates) {
            var error = assertThrows(CaptureFailure.class, () -> worker((now, limit, after) -> values, 1).tick(owner, LEASE, RECOVER));
            assertEquals(CaptureFailure.Reason.UNAVAILABLE, error.reason()); assertNull(error.getCause());
        }
        var error = assertThrows(CaptureFailure.class, () -> worker((now, limit, after) -> { throw new IllegalStateException("private provider"); }, 1).tick(owner, LEASE, RECOVER));
        assertEquals("UNAVAILABLE", error.getMessage()); assertNull(error.getCause()); verifyNoInteractions(handler);
    }
    @Test void stopAndExpiredAbsoluteHorizonsAfterDiscoveryPreventSourceStarts() {
        var stopped = new AtomicBoolean();
        var stopOperation = worker((now, limit, after) -> { stopped.set(true); return List.of(id(1)); }, 1);
        assertTrue(stopOperation.tick(owner, LEASE, RECOVER, stopped::get).interrupted());
        var expireOperation = worker((now, limit, after) -> { clock.now = RECOVER; return List.of(id(1)); }, 1);
        var result = expireOperation.tick(owner, LEASE, RECOVER);
        assertTrue(result.horizonExpired()); assertEquals(0, result.attempted()); verifyNoInteractions(handler);
    }
    @Test void budgetAndExpiryStopNewStartsWithoutExtendingCallerHorizons() {
        var budgetOperation = new PreparationWorker((now, limit, after) -> List.of(id(1)), handler, clock, 1, Duration.ofNanos(1));
        var budget = budgetOperation.tick(owner, LEASE, RECOVER);
        assertTrue(budget.budgetExhausted()); assertEquals(0, budget.attempted());
        when(handler.prepare(any(), any())).thenAnswer(invocation -> { clock.now = LEASE; return null; });
        var expired = worker((now, limit, after) -> List.of(id(1), id(2)), 2).tick(owner, LEASE, RECOVER);
        assertEquals(1, expired.prepared()); assertTrue(expired.horizonExpired());
        verify(handler, times(1)).prepare(new Claim(id(1), owner, LEASE), RECOVER);
    }
    @Test void invalidInputsAreRejectedBeforeDiscovery() {
        var ready = mock(PreparationReadyReceipts.class); var operation = worker(ready, 1);
        var invalid = assertThrows(CaptureFailure.class, () -> operation.tick(owner, NOW, RECOVER));
        assertEquals(CaptureFailure.Reason.INVALID_INPUT, invalid.reason());
        assertThrows(CaptureFailure.class, () -> operation.tick(null, LEASE, RECOVER));
        assertThrows(CaptureFailure.class, () -> operation.tick(owner, LEASE, NOW));
        verifyNoInteractions(ready, handler);
    }
    @Test void horizonExpiringInsidePreparationIsReportedAsExpiredRatherThanUnsupportedSource() {
        when(handler.prepare(any(), any())).thenAnswer(invocation -> {
            clock.now = LEASE;
            throw new CaptureFailure(CaptureFailure.Reason.INVALID_INPUT);
        });
        var result = worker((now, limit, after) -> List.of(id(1), id(2)), 2).tick(owner, LEASE, RECOVER);
        assertEquals(1, result.attempted()); assertEquals(1, result.stale());
        assertEquals(0, result.unsupported()); assertTrue(result.horizonExpired());
        verify(handler, times(1)).prepare(new Claim(id(1), owner, LEASE), RECOVER);
    }
}
