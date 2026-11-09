package org.pipesor.ingestion.configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pipesor.ingestion.github.application.ports.CurrentGitHubLink;
import org.pipesor.ingestion.github.application.ports.InstallationAdmission;
import org.pipesor.ingestion.github.application.ports.Observation;
import org.pipesor.ingestion.github.application.ports.ReadOnlyGitHub;
import org.pipesor.ingestion.github.application.queries.GitHubViews;
import org.pipesor.ingestion.github.application.queries.VerifyAccessQuery;
import org.pipesor.ingestion.github.application.queryhandlers.GitHubQueryHandler;
import org.pipesor.ingestion.github.application.services.GitHubAccessFailure;
import org.pipesor.ingestion.github.application.services.GitHubQueries;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessContext;
import org.pipesor.ingestion.github.domain.model.valueobjects.AccessOperation;
import org.pipesor.ingestion.github.domain.model.valueobjects.GitHubLink;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryFacts;
import org.pipesor.ingestion.github.domain.model.valueobjects.RepositoryTarget;
import org.pipesor.ingestion.github.domain.model.valueobjects.WorkflowMetadata;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class GitHubConfigurationTests {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AccessContext CONTEXT = new AccessContext("tenant", "actor", "actor", AccessOperation.CONFIGURE, "corr-compose");
    private static final RepositoryTarget TARGET = new RepositoryTarget("repo-42", "installation-17");
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubConfiguration.class);

    @Test
    void composesRealHandlerWithMandatoryPortsAndTestClockAndPreservesIdentityChecks() {
        var ports = new SyntheticPorts();
        withPorts(ports, null).run(context -> {
            assertNull(context.getStartupFailure());
            var queries = context.getBean(GitHubQueries.class);
            assertInstanceOf(GitHubQueryHandler.class, queries);
            assertEquals(ZoneOffset.UTC, context.getBean("githubClock", Clock.class).getZone());
            var verified = queries.verifyAccess(new VerifyAccessQuery(CONTEXT, Set.of(TARGET)));
            assertEquals(CONTEXT, verified.context());
            assertEquals(NOW, verified.observedAt());
            assertEquals(new GitHubViews.RepositoryAccess(true, true, false), verified.repositories().get(TARGET));
            ports.actualUser = "another-user";
            var failure = assertThrows(GitHubAccessFailure.class,
                    () -> queries.verifyAccess(new VerifyAccessQuery(CONTEXT, Set.of(TARGET))));
            assertEquals(GitHubAccessFailure.Reason.CONNECTION_REQUIRED, failure.reason());
        });
    }

    @ParameterizedTest
    @ValueSource(classes = {CurrentGitHubLink.class, InstallationAdmission.class, ReadOnlyGitHub.class})
    void missingEachProductionPortFailsStartupWithThatExactDependency(Class<?> missingPort) {
        withPorts(new SyntheticPorts(), missingPort).run(context -> {
            Throwable cause = context.getStartupFailure();
            assertNotNull(cause);
            while (cause.getCause() != null) { cause = cause.getCause(); }
            assertEquals(NoSuchBeanDefinitionException.class, cause.getClass());
            var missing = assertInstanceOf(NoSuchBeanDefinitionException.class, cause);
            assertEquals(missingPort, missing.getBeanType());
        });
    }

    private ApplicationContextRunner withPorts(SyntheticPorts ports, Class<?> missing) {
        var configured = runner.withBean("testClock", Clock.class, () -> CLOCK, definition -> definition.setPrimary(true));
        if (missing != CurrentGitHubLink.class) { configured = configured.withBean(CurrentGitHubLink.class, () -> ports::link); }
        if (missing != InstallationAdmission.class) { configured = configured.withBean(InstallationAdmission.class, () -> ports::admission); }
        if (missing != ReadOnlyGitHub.class) { configured = configured.withBean(ReadOnlyGitHub.class, () -> ports); }
        return configured;
    }

    private static final class SyntheticPorts implements ReadOnlyGitHub {
        private String actualUser = "github-user";

        Observation<AccessContext, Optional<GitHubLink>> link(AccessContext request) {
            return observed(request, Optional.of(new GitHubLink(request.tenantId(), request.subjectRef(),
                    "github-user", "revision-1", NOW.minusSeconds(60))));
        }

        Observation<InstallationAdmission.Request, Boolean> admission(InstallationAdmission.Request request) {
            return observed(request, request.identity().context().tenantId().equals("tenant")
                    && request.repository().equals(TARGET) && request.accountId().equals("account-3"));
        }

        @Override
        public Observation<Identity, String> currentUser(Identity request) { return observed(request, actualUser); }

        @Override
        public Observation<RepositoryRequest, RepositoryFacts> repository(RepositoryRequest request) {
            return observed(request, new RepositoryFacts(TARGET, "account-3", true, true, true, true,
                    false, "example/service", null));
        }

        @Override
        public Observation<Identity, List<RepositoryFacts>> repositories(Identity request) {
            throw new AssertionError("Not used by the composition test");
        }

        @Override
        public Observation<RepositoryRequest, List<WorkflowMetadata>> workflows(RepositoryRequest request) {
            throw new AssertionError("Not used by the composition test");
        }

        @Override
        public Observation<WorkflowRequest, WorkflowMetadata> workflow(WorkflowRequest request) {
            throw new AssertionError("Not used by the composition test");
        }

        private <Q, T> Observation<Q, T> observed(Q request, T value) { return new Observation<>(request, value, NOW); }
    }
}
