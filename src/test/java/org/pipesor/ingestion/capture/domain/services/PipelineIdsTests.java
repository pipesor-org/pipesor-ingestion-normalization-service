package org.pipesor.ingestion.capture.domain.services;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelineIdsTests {
    @Test void matchesPipelineDeterministicIdentifiers() {
        // Independently computed with Python uuid.uuid5(NAMESPACE_URL, ...) over Pipeline's names.
        var execution = PipelineIds.execution("1446318020", "43055757606", 1);
        assertEquals(UUID.fromString("525a9917-04d9-5bef-ab66-7f78de5d5e87"), execution);
        assertEquals(UUID.fromString("b9a0ec82-9ddb-5f2b-944e-6d31a0d3bb90"), PipelineIds.incident(execution));
        assertNotEquals(execution, PipelineIds.execution("1446318020", "43055757606", 2));
        assertThrows(IllegalArgumentException.class, () -> PipelineIds.execution("0123", "1", 1));
        assertThrows(IllegalArgumentException.class, () -> PipelineIds.execution("1", "1", 0));
    }
}
