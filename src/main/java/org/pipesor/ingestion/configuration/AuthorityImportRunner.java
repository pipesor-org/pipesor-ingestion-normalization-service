package org.pipesor.ingestion.configuration;

import java.nio.file.Path;
import java.time.Clock;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.infrastructure.persistence.jdbc.JdbcCaptureAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-023 operator command: {@code --pipesor.admin.import-authority=<reviewed authority file>} upserts
 * repository admissions and legacy workflow scopes in one transaction, then closes the application context.
 * Run it with {@code --spring.main.web-application-type=none} so no listener or worker starts.
 */
final class AuthorityImportRunner implements ApplicationRunner {
    static final String OPTION = "pipesor.admin.import-authority";
    private static final Logger LOG = LoggerFactory.getLogger(AuthorityImportRunner.class);

    private final GitHubProperties github;
    private final JdbcCaptureAuthority authority;
    private final PlatformTransactionManager transactions;
    private final ConfigurableApplicationContext context;
    private final Clock clock;

    AuthorityImportRunner(GitHubProperties github, JdbcCaptureAuthority authority, PlatformTransactionManager transactions,
            ConfigurableApplicationContext context, Clock clock) {
        this.github = github; this.authority = authority; this.transactions = transactions; this.context = context; this.clock = clock;
    }

    @Override public void run(ApplicationArguments arguments) {
        var values = arguments.getOptionValues(OPTION);
        if (values == null || values.isEmpty()) return;
        if (values.size() != 1) throw new IllegalArgumentException("Exactly one --" + OPTION + " is required");
        var file = new GitHubFileAuthority(Path.of(values.getFirst()), github.appId(), clock,
                github.limits().authorityMaxFileBytes(), github.limits().authorityMaxEntries());
        var export = file.export();
        var now = clock.instant();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (var admission : export.admissions()) {
                for (var repository : admission.repositories()) {
                    authority.importAdmission(admission.tenant(), admission.account(), admission.installation(), repository,
                            "operator-import", now);
                }
            }
            for (var scope : export.scopes()) authority.importScope(scope.scope(), scope.actions(), scope.replay(), now);
        });
        LOG.info("Authority import completed: admissions={} scopes={}", export.admissions().stream()
                .mapToInt(a -> a.repositories().size()).sum(), export.scopes().size());
        SpringApplication.exit(context, () -> 0);
    }
}
