package org.pipesor.ingestion.configuration;

import java.nio.file.Path;
import java.util.List;

/** Shape-valid local GitHub settings for contexts that bind {@link GitHubProperties} without contacting GitHub. */
final class GitHubTestSettings {
    private GitHubTestSettings() { }

    static List<String> properties(Path directory) {
        return List.of("pipesor.github.app-id=120", "pipesor.github.capture-domain=pilot",
                "pipesor.github.authority-file=" + directory.resolve("authority.json"),
                "pipesor.github.callback-url=http://127.0.0.1:8081/api/v1/auth/github/callback",
                "pipesor.github.private-key-file=" + directory.resolve("app.pem"), "pipesor.github.private-key-sha256=" + "0".repeat(64),
                "pipesor.github.client-secret-file=" + directory.resolve("client-secret"),
                "pipesor.github.webhook-secret-file=" + directory.resolve("webhook-secret"),
                "pipesor.github.custody-directory=" + directory.resolve("custody"));
    }
}
