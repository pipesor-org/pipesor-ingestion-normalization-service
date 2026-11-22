package org.pipesor.ingestion.configuration;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

/** Typed settings fail fast with the setting name and never fall back to a disabled feature. */
class ConfigurationPropertiesTests {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({GitHubProperties.class, CaptureProperties.class, ServiceBusProperties.class, SessionProperties.class, ApiProperties.class})
    static class Bound { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Bound.class)
            .withPropertyValues(GitHubTestSettings.properties(Path.of("/synthetic")).toArray(String[]::new))
            .withPropertyValues("pipesor.session.signing-key-file=/synthetic/session.pem", "pipesor.session.dashboard-origin=http://127.0.0.1:5173",
                    "pipesor.service-bus.connection-string=Endpoint=sb://127.0.0.1;SharedAccessKeyName=a;SharedAccessKey=b");

    @Test void defaultsCompleteAValidLocalConfiguration() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var github = context.getBean(GitHubProperties.class);
            assertEquals(URI.create("https://api.github.com"), github.apiBaseUrl());
            assertEquals("2022-11-28", github.apiVersion());
            assertEquals("workflow-evidence", context.getBean(ServiceBusProperties.class).topic());
            assertEquals(Duration.ofHours(1), context.getBean(SessionProperties.class).lifetime());
            assertTrue(context.getBean(CaptureProperties.class).workersEnabled());
        });
    }

    @Test void apiBaseUrlIsConfigurable() {
        runner.withPropertyValues("pipesor.github.api-base-url=https://ghe.example/").run(context ->
                assertEquals(URI.create("https://ghe.example/"), context.getBean(GitHubProperties.class).apiBaseUrl()));
    }

    @Test void missingOrAmbiguousSettingsFailWithTheirNames() {
        assertFailure(runner.withPropertyValues("pipesor.github.app-id="), "pipesor.github.app-id");
        assertFailure(runner.withPropertyValues("pipesor.github.key-vault-url=https://vault.example"), "pipesor.github.private-key-file");
        assertFailure(runner.withPropertyValues("pipesor.github.custody-vault-url=https://custody.example"), "pipesor.github.custody-directory");
        assertFailure(runner.withPropertyValues("pipesor.service-bus.namespace=bus.servicebus.windows.net"), "pipesor.service-bus.connection-string");
        assertFailure(runner.withPropertyValues("pipesor.session.signing-key-id=https://vault.example/keys/pipesor-session-signing/1"), "pipesor.session.signing-key-file");
        assertFailure(runner.withPropertyValues("pipesor.session.lifetime=2h"), "pipesor.session.lifetime");
        assertFailure(runner.withPropertyValues("pipesor.session.dashboard-origin=http://127.0.0.1:5173/app"), "pipesor.session.dashboard-origin");
        assertFailure(runner.withPropertyValues("pipesor.capture.protection.version=has space"), "pipesor.capture.protection");
    }

    private static void assertFailure(ApplicationContextRunner configured, String setting) {
        configured.run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, setting);
            var messages = new StringBuilder();
            for (; failure != null; failure = failure.getCause()) messages.append(failure.getMessage()).append('\n');
            assertTrue(messages.toString().contains(setting), messages.toString());
        });
    }
}
