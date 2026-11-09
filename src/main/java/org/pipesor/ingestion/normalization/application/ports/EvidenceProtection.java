package org.pipesor.ingestion.normalization.application.ports;

import java.util.Optional;

/**
 * Required policy boundary: mask/anonymize/filter/minimize every free text before serialization.
 * Empty means policy refusal. Implementations must not log source text or attach it to failures.
 * This contract is not a sanitizer implementation; no production pass-through is supplied.
 */
@FunctionalInterface
public interface EvidenceProtection {
    Optional<Texts> protect(Texts source);

    record Texts(String branch, String service, String environment, String failureSummary, String job, String excerpt,
            String correlationId, String scopeRevision) {
        @Override public String toString() { return "Texts[redacted]"; }
    }
}
