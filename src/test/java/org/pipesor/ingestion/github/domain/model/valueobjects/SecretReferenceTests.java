package org.pipesor.ingestion.github.domain.model.valueobjects;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SecretReferenceTests {
    @ParameterizedTest
    @ValueSource(strings = {"latest", "LATEST", " latest "})
    void confirmationRequiresAnExactVersionInsteadOfAnAlias(String alias) {
        var object = SecretReference.generationObject(UUID.randomUUID());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new SecretReference.SavedVersion(object, new SecretReference(alias)));
        assertEquals("Exact secret version required", failure.getMessage());
    }
}
