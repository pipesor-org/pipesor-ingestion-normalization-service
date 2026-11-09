package org.pipesor.ingestion.normalization.infrastructure.protection;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.configuration.EvidenceProtectionConfiguration;
import org.pipesor.ingestion.configuration.NormalizationConfiguration;
import org.pipesor.ingestion.normalization.application.commandhandlers.NormalizeObservationHandler;
import org.pipesor.ingestion.normalization.application.ports.EvidenceProtection;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceProtectionConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EvidenceProtectionConfiguration.class);
    private static final List<String> SETTINGS = List.of("ingestion.protection.enabled=true", "ingestion.protection.version=known-patterns-v1",
            "ingestion.protection.max-input-bytes=65536", "ingestion.protection.max-output-chars=32768", "ingestion.protection.max-redactions=100");

    @Test
    void absentOrDisabledPolicyProvidesNoFallback() {
        runner.run(context -> { assertNull(context.getStartupFailure()); assertTrue(context.getBeansOfType(EvidenceProtection.class).isEmpty()); });
        runner.withPropertyValues("ingestion.protection.enabled=false").run(context -> assertTrue(context.getBeansOfType(EvidenceProtection.class).isEmpty()));
        runner.withUserConfiguration(NormalizationConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(NormalizeObservationHandler.class).isEmpty());
        });
    }

    @Test
    void explicitConfigurationProvidesTheActualPolicyAndNormalHandler() {
        runner.withUserConfiguration(NormalizationConfiguration.class).withPropertyValues(SETTINGS.toArray(String[]::new)).run(context -> {
            assertNull(context.getStartupFailure());
            var policy = assertInstanceOf(BoundedEvidenceProtection.class, context.getBean(EvidenceProtection.class));
            assertEquals("known-patterns-v1", policy.policyVersion());
            assertEquals(65536, policy.maxInputBytes());
            assertEquals(32768, policy.maxOutputChars());
            assertEquals(100, policy.maxRedactions());
            assertNotNull(context.getBean(NormalizeObservationHandler.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "max-input-bytes", "max-output-chars", "max-redactions"})
    void eachRequiredSettingMissingFailsEnabledStartup(String missing) {
        var settings = new ArrayList<>(SETTINGS);
        settings.removeIf(setting -> setting.startsWith("ingestion.protection." + missing + "="));
        runner.withPropertyValues(settings.toArray(String[]::new)).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertEquals("Required evidence protection configuration missing or invalid", root(context.getStartupFailure()).getMessage());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"version=hidden secret", "max-input-bytes=hidden-secret", "max-input-bytes=0", "max-output-chars=-1", "max-redactions=0"})
    void invalidConfigurationHasFixedDiagnosticsWithoutLeakingValuesOrCauses(String invalid) {
        var key = invalid.substring(0, invalid.indexOf('='));
        var settings = new ArrayList<>(SETTINGS);
        settings.removeIf(setting -> setting.startsWith("ingestion.protection." + key + "="));
        settings.add("ingestion.protection." + invalid);
        runner.withPropertyValues(settings.toArray(String[]::new)).run(context -> {
            var failure = root(context.getStartupFailure());
            assertEquals("Required evidence protection configuration missing or invalid", failure.getMessage());
            assertNull(failure.getCause());
            assertFalse(context.getStartupFailure().toString().contains("hidden"));
        });
    }

    private static Throwable root(Throwable failure) { assertNotNull(failure); while (failure.getCause() != null) { failure = failure.getCause(); } return failure; }
}
