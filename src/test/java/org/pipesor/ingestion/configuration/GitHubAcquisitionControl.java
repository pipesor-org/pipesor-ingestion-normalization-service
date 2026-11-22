package org.pipesor.ingestion.configuration;

import com.azure.identity.AzureCliCredentialBuilder;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.pipesor.ingestion.capture.application.ports.CurrentMachineScope;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Job;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Request;
import org.pipesor.ingestion.capture.application.ports.GitHubMachineCapture.Run;
import org.pipesor.ingestion.capture.domain.exceptions.CaptureFailure;
import org.pipesor.ingestion.capture.domain.model.valueobjects.CaptureScope;
import org.pipesor.ingestion.capture.infrastructure.configuration.GitHubFileAuthority;
import org.pipesor.ingestion.capture.infrastructure.credentials.LocalAppSigningKey;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubAppJwtSigner;
import org.pipesor.ingestion.capture.infrastructure.github.GitHubMachineTransport;
import org.pipesor.ingestion.capture.interfaces.webhook.GitHubWorkflowWebhook.HookContext;
import org.pipesor.ingestion.github.infrastructure.custody.KeyVaultCredentialCustody;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

/** Manual read-only control. No receipt, listener, publisher or automatic test discovery. */
public final class GitHubAcquisitionControl {
    private record Control(String workflow, String conclusion, Set<String> jobs) {
        void verify(Run run) {
            var actual = run.jobs().stream().map(Job::id).collect(Collectors.toSet());
            if (!jobs.equals(actual) || !"completed".equals(run.status()) || !conclusion.equals(run.conclusion())) {
                throw new CaptureFailure(CaptureFailure.Reason.CONFLICT);
            }
        }
    }
    private static final Map<String, Control> CONTROLS = Map.of(
            "42843891934", new Control("412504984", "success", Set.of("129926055075")),
            "43055757606", new Control("412504984", "failure", Set.of("130644639649")),
            "43064316120", new Control("414901007", "failure", Set.of()),
            "44648382520", new Control("414901007", "success", Set.of("135996727127", "135997834911")));

    public static void main(String[] args) {
        if (args.length != 2 || !CONTROLS.containsKey(args[1])) {
            System.err.println("Expected an operator properties file and a documented control run ID.");
            System.exit(2);
        }
        try {
            var env = new MockEnvironment();
            env.getPropertySources().addFirst(new ResourcePropertySource(new FileSystemResource(args[0])));
            if (env.containsProperty("control.signing-key-file")) {
                checkLocal(env, args[1]);
            } else {
                check(env, args[1]);
            }
        } catch (CaptureFailure failure) {
            System.err.println("Acquisition refused: " + failure.reason());
            System.exit(1);
        } catch (Exception unavailable) {
            System.err.println("Acquisition configuration or authority unavailable.");
            System.exit(1);
        }
    }

    static void check(MockEnvironment env, String runId) {
        if (!"true".equals(env.getProperty("ingestion.github-machine.enabled"))) {
            throw new IllegalArgumentException();
        }
        var machine = new GitHubMachineConfiguration();
        var settings = machine.githubMachineSettings(env);
        if (!settings.appId().equals("5722735")) throw new IllegalArgumentException();
        var clock = Clock.systemUTC();
        var file = machine.githubFileAuthority(settings, clock);
        var route = new HookContext(settings.domain(), settings.tenant(), settings.appId());
        var control = CONTROLS.get(runId);
        var scope = new CaptureScope(route.captureDomain(), route.tenantId(), route.appId(), "341855203",
                "181117842", "1446318020", control.workflow(), env.getRequiredProperty("control.scope-revision"));
        CurrentMachineScope authority = candidate -> file.verifyProtectedEvidenceScope(route, candidate.context(), candidate.pipeline());
        var vault = KeyVaultCredentialCustody.secretClient(settings.vault(), settings.vaultTimeout(), () -> new AzureCliCredentialBuilder()
                .tenantId(UUID.fromString(env.getRequiredProperty("control.azure-tenant")).toString())
                .subscription(UUID.fromString(env.getRequiredProperty("control.azure-subscription")).toString())
                .processTimeout(settings.vaultTimeout()).build());
        var signer = machine.githubAppJwtSigner(settings, machine.githubAppMaterial(settings, vault), clock);
        var preparation = new CapturePreparationConfiguration();
        var profile = preparation.failedRunEvidenceProfile(env);
        var transport = new GitHubMachineTransport(authority, signer, settings.appId(), settings.version(),
                settings.operationBudget(), settings.pageSize(), settings.maxPages(), settings.maxJobs(),
                settings.maxResponseBytes(), settings.maxLogBytes(), preparation.captureArchiveLimits(env),
                settings.downloadHosts(), clock);
        classify(scope, runId, control, transport, profile);
    }

    private static void checkLocal(MockEnvironment env, String runId) {
        if (!"true".equals(env.getProperty("ingestion.github-machine.enabled"))) {
            throw new IllegalArgumentException();
        }
        var clock = Clock.systemUTC();
        var tenant = UUID.fromString(env.getRequiredProperty("ingestion.github-machine.webhook-tenant"));
        var domain = env.getRequiredProperty("ingestion.github-machine.capture-domain");
        var file = new GitHubFileAuthority(Path.of(env.getRequiredProperty("ingestion.github-machine.authority-file")),
                "5722735", clock, 65536, 64);
        file.validate();
        var route = new HookContext(domain, tenant, "5722735");
        var control = CONTROLS.get(runId);
        var scope = new CaptureScope(domain, tenant, route.appId(), "341855203", "181117842",
                "1446318020", control.workflow(), env.getRequiredProperty("control.scope-revision"));
        CurrentMachineScope authority = candidate -> file.verifyProtectedEvidenceScope(route, candidate.context(), candidate.pipeline());
        var keyFile = Path.of(env.getRequiredProperty("control.signing-key-file"));
        var keyDigest = env.getRequiredProperty("control.signing-key-sha256");
        var signer = new GitHubAppJwtSigner(route.appId(), () -> LocalAppSigningKey.read(keyFile, keyDigest), clock,
                Duration.ofMinutes(4), Duration.ofSeconds(30));
        var preparation = new CapturePreparationConfiguration();
        var transport = new GitHubMachineTransport(authority, signer, route.appId(),
                env.getRequiredProperty("ingestion.github-machine.api-version"), Duration.ofSeconds(35),
                100, 2, 100, 4_000_000, 1_000_000, preparation.captureArchiveLimits(env),
                Set.of("results-receiver.actions.githubusercontent.com"), clock);
        classify(scope, runId, control, transport, preparation.failedRunEvidenceProfile(env));
    }

    private static void classify(CaptureScope scope, String runId, Control control, GitHubMachineTransport transport,
            org.pipesor.ingestion.capture.application.services.FailedRunEvidenceProfile profile) {
        var request = new Request(scope, runId, 1, UUID.randomUUID().toString());
        if (control.jobs().isEmpty()) {
            control.verify(transport.readRun(request));
            System.out.printf("workflow=%s run=%s attempt=1 jobs=0%n", scope.workflowId(), runId);
        }
        var source = transport.readStepLogs(request);
        control.verify(source.run());
        System.out.printf("workflow=%s run=%s attempt=1 jobs=%d updatedAt=%s boundStepLogs=%d%n",
                scope.workflowId(), runId, source.run().jobs().size(), source.run().updatedAt(), source.logs().size());
        try {
            var result = profile.classify(source);
            System.out.println(result.observation().evidence() != null && result.observation().evidence().sourceLocator() != null
                    ? "SUPPORTED aggregate evidence" : "SUPPORTED by Verify v1");
            if (result.fragmentLocator() != null) {
                System.out.println(result.fragmentLocator());
            }
        } catch (CaptureFailure failure) {
            if (failure.reason() != CaptureFailure.Reason.INVALID_INPUT) throw failure;
            System.out.println("UNSUPPORTED by preparation profiles");
        }
    }
}
