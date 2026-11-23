# pipesor-ingestion-normalization-service

Ingestion and Normalization Service captures authorized workflow evidence, protects sensitive data and publishes normalized events with provenance and `correlationId` to Azure Service Bus. Its initial source is GitHub Actions. Its canonical architecture ID is `ingestion_normalization_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The [approved ADR-020 evolution](docs/architecture.md#approved-github-access-evolution) also assigns GitHub user connection, discovery and verified access/metadata to this service; DD-020 additionally records the locally approved metadata/custody design. Runtime identity, callback, admission, recovery and delivery inputs remain distinct from that approval.

This repository contains a Java 25 / Spring Boot application (one image, profiles `local` and `azure`) with GitHub facts/discovery, the GitHub login and session issuance of the [platform identity contract v1](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/contracts/platform-identity-v1.md) (ADR-022), PostgreSQL connector metadata, bounded evidence protection, normalized message preparation and an owned durable capture/publication ledger. Ingestion reports current admitted/readable/administrator facts; Pipeline owns registration, grants and the decision about AIOps capabilities. Every inbound request is verified by the platform decoder: human routes require a Pipesor session token with scope `GitHub.Self`; Pipeline's internal routes require its workload token with the catalog role (plus the user's session as subject token for GitHub facts). Capture requests only record durable intent; the preparation and publication workers protect, freeze and publish to Azure Service Bus. See [Runtime configuration](#runtime-configuration), [Routes](#routes) and [Local login walkthrough](#local-login-walkthrough).

The [GitHub read-provider boundary](docs/deployment-contract.md#github-read-provider-implementation-boundary) uses user access tokens, complete bounded pagination and exact request/generation binding. It refuses partial/ambiguous provider results and has no runtime bean or production credential supplier. This does not activate a real connection or prove restore safety.

The [durable publication worker](docs/deployment-contract.md#durable-publication-worker) scans ready origin receipts and sends frozen outputs through the existing scope/claim/fencing path. It rotates past failing work, enforces bounded ticks and runs with the preparation runner in every deployment (`pipesor.capture.workers-enabled=false` exists only for automated tests).

The opt-in [machine/webhook adapters](docs/deployment-contract.md#machine-and-webhook-configuration-handoff) verify the App/installation and exact attempt, protect signed-download credentials and authenticate original webhook bytes before durable acceptance. The approved public webhook route is absent by default and has explicit body, time and in-flight limits. The operator-owned configuration is reread on every authorization check; failure never reuses a last-good authorization snapshot. The opt-in preparation handler reads bounded attempt archives in memory, applies the [approved Verify profile](docs/deployment-contract.md#verify-preparation-profile-v1--approved-locally), and freezes a protected plan with reserved message IDs. It runs for an explicitly supplied receipt/claim and recovery horizon. The [preparation dispatcher](docs/deployment-contract.md#explicit-preparation-dispatch--local-implementation) can select a bounded batch using caller-supplied absolute horizons; it is disabled by default and installs no scheduler, HTTP preparation command or automatic recovery by itself. The separately enabled [preparation scheduler](docs/deployment-contract.md#continuous-preparation-scheduling--approved-locally) computes fixed horizons at each batch start, prevents overlap and uses bounded shutdown; it introduces no automatic discard or unblock. This does not imply the complete capture → preparation → publication path is active.

## Documentation

- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Deployment contract](docs/deployment-contract.md): development baseline, delivery requirements and unresolved contracts.
- [Cross-service contracts](docs/deployment-contract.md#cross-service-contract-proposals--2026-11-08): locally approved execution/evidence, routing and connector design; consumer adaptation and concrete capture/browser/runtime inputs remain pending.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Read-only GitHub acquisition control

From this checkout, after an operator supplies the authority file and a pinned local App key (see the end of [Full local delivery verification](#full-local-delivery-verification)), run one named historical control:

```bash
./mvnw -B -ntp test-compile org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath -DincludeScope=test -Dmdep.outputFile=target/acquisition-test.cp
java -cp "target/test-classes:target/classes:$(cat target/acquisition-test.cp)" \
  org.pipesor.ingestion.configuration.GitHubAcquisitionControl "$PIPESOR_CONTROL_PROPERTIES" 42843891934
```

The test-classpath command uses the actual Java machine transport and file-backed authority. Its only outputs on success are source identifiers, job and bound-step counts, and the Verify profile's classification. An unsupported profile after a successful read exits zero. An unavailable or denied read exits nonzero. The command starts no application, ledger, publisher or workflow.

## Development and verification

The [development baseline](docs/deployment-contract.md#development-baseline) records the selected toolchain and dependency policy. The [entry point](src/main/java/org/pipesor/ingestion/IngestionServiceApplication.java) and the `configuration` package ([GitHub](src/main/java/org/pipesor/ingestion/configuration/GitHubConfiguration.java), [capture](src/main/java/org/pipesor/ingestion/configuration/CaptureConfiguration.java), [messaging](src/main/java/org/pipesor/ingestion/configuration/MessagingConfiguration.java), [session](src/main/java/org/pipesor/ingestion/configuration/SessionConfiguration.java), [routes](src/main/java/org/pipesor/ingestion/configuration/WebConfiguration.java)) compose every adapter explicitly; there are no nullable beans or disabled fallbacks. Domain/application and shared domain identity remain independent of Spring, HTTP and ORM.

Metadata commands prepare a connection generation and OAuth attempt, record protected PKCE/version confirmations, consume attempts once, activate with revision guards, record refresh phases and invalidate with durable cleanup intent. [JPA persistence](src/main/java/org/pipesor/ingestion/github/infrastructure/persistence/jpa/JpaConnectionRepository.java) loads the current/target state and nonterminal guards rather than the full history; an infrastructure transaction wraps each complete local command. [V1](src/main/resources/db/migration/V1__github_connection_metadata.sql) owns only the `ingestion` schema and derives from canonical domain 1.2.0 / DD-020. It creates no roles, grants or downstream tables.

The [normalization handler](src/main/java/org/pipesor/ingestion/normalization/application/commandhandlers/NormalizeObservationHandler.java) prepares a complete in-memory output plan from a whitelisted internal observation, independently supplied expected capture scope and caller-reserved message IDs. It rejects scope mismatches before protection. The [mandatory protection port](src/main/java/org/pipesor/ingestion/normalization/application/ports/EvidenceProtection.java) must inspect/mask/minimize free text and may refuse processing; the opt-in [bounded known-pattern protector](src/main/java/org/pipesor/ingestion/normalization/infrastructure/protection/BoundedEvidenceProtection.java) filters recognized credentials, email/IP identifiers and sensitive assignments, and refuses unsupported or oversized evidence. It does not detect arbitrary personal information or unknown secret encodings. There is no pass-through adapter. Correlation and scope revision must remain exact or fail. Validation is not authentication: the machine adapter verifies the actual GitHub run/attempt and current scope, while its raw output still requires a reviewed extraction/stage mapping before normalization.

The [message transformer](src/main/java/org/pipesor/ingestion/normalization/interfaces/messaging/transform/NormalizedMessagesTransformer.java) accepts only a result created after protection and produces Pipeline `execution.snapshot` v1 and, for failed/timed_out attempts, protected evidence v2 with coherent broker IDs/routing and defensive JSON bytes. Failed attempts require classified evidence; success/nonterminal attempts produce only a snapshot. Jackson 3.1.7 is selected by the Boot BOM and is confined to the wire adapter. The normalizer prepares immutable in-memory values; the capture ledger below owns ID reservations and freezes outputs for retries. The preparation coordinator uses those reserved IDs; a standalone normalization call is not durable acceptance.

The [capture handler](src/main/java/org/pipesor/ingestion/capture/application/commandhandlers/CaptureHandler.java) and [JPA ledger](src/main/java/org/pipesor/ingestion/capture/infrastructure/persistence/jpa/JpaCaptureLedger.java) now provide explicit acceptance, preparation and per-output publication operations. Acceptance commits the receipt, delivery alias and reserved message UUIDs; preparation freezes protected observations and the complete one/two-output plan. Short transactions, leases/fences and a common lock order protect concurrent workers. Publication calls the required current-machine-scope and broker ports outside SQL transactions, then records confirmation. The production ports have no successful default adapter and no worker/scheduler or webhook is activated. The API accepts trusted internal source metadata; a constructor or supplied digest does not authenticate GitHub.

[V2](src/main/resources/db/migration/V2__capture_publication_ledger.sql) derives from integrated Docs domain 1.3.0 / DD-021 and adds four owner-local tables without changing V1. Retries read frozen IDs/bytes rather than rerun normalization; an uncertain acknowledgement may cause duplicate delivery under the existing consumer contracts. Only confirmed bodies past the explicitly supplied recovery horizon can be removed; receipt/dedup metadata and blocked/pending work are retained. No retention duration, metadata purge policy or live deployment input is inferred. Original source nanoseconds are preserved using microsecond timestamp columns plus submicrosecond companions.

Requires Java 25 and Docker for the PostgreSQL integration checks. The wrapper pins Maven 3.10.0 with a distribution SHA-256; Boot 4.1.2 and compatible dependencies are pinned in [pom.xml](pom.xml). Verified build command with local caches:

```bash
MAVEN_USER_HOME=/tmp/pipesor-ingestion-maven ./mvnw -B -ntp -Dmaven.repo.local=/tmp/pipesor-ingestion-m2 clean verify
```

This compiles, runs isolated behavior/composition and real PostgreSQL tests, and packages the Boot JAR. Testcontainers uses PostgreSQL 17.11; tests explicitly run Flyway and validate JPA mappings. They exercise uniqueness/FK/partial/deferred constraints, concurrent activation/claims, expiry/replay, fencing, rollback, generation isolation, late acknowledgements and fixed failure causes without private references. Only test sources supply GitHub/link/admission doubles and synthetic custody confirmations; no GitHub, Entra or Key Vault is contacted. Docker absence is a test failure, not a successful skipped integration check. With writable normal Maven caches, the equivalent command is `./mvnw -B -ntp clean verify`; the reported checks use the explicit cache paths above.

Verification on 2026-11-07: the wrapper command passed all 96 tests (64 query behavior, 4 isolated composition, 25 PostgreSQL metadata and 3 exact-version checks), with no failures, errors or skips, and packaged the Boot JAR. The coordinated canonical Docs changes also passed generation, 106 unit tests and 9 SQL checks on PostgreSQL 16.15 with pgvector 0.8.7. These are local checks; the different database versions do not establish production compatibility or live provider integration.

Verification on 2026-11-08: the same wrapper `clean verify` passed 122 tests with no failures/errors/skips, including 26 normalization checks and the existing 25 PostgreSQL 17.11 metadata checks. Forty domain/application/shared-domain sources compiled with an empty dependency classpath; `jdeps` reported `java.base` only. An isolated compilation of Pipeline's actual snapshot transformer/domain sources accepted both generated snapshots (SHA40/SHA64, distinct attempts and huge textual IDs), with matching owner UUIDv5 rules and nanosecond timestamps. This checks decoder compatibility, not Pipeline's database processing or live messaging. NLP's current v1 domain parser rejected both generated evidence v2 bodies with `unsupported_schema_version`, confirming its adaptation gate. The packaged JAR includes Jackson and excludes fixtures, tests and the externally compiled consumer classes.

Verification on 2026-11-09: wrapper `clean verify` passed 149 tests with no failures/errors/skips, including 24 real PostgreSQL 17.11 capture tests, 25 connector-persistence tests and 3 fingerprint checks. The final full build includes exact SQLState/constraint assertions and regressions for retry deadlines elapsed during I/O and late scope denial after claim takeover. The 51 core sources compiled with an empty classpath and `jdeps` reported only `java.base`. Actual Docs generation, 109 unit tests and 14 PostgreSQL 16.15/pgvector 0.8.7 checks passed. V2 matches the actual canonical SQL export and lifecycle guards byte-for-byte. These checks use synthetic source/protection/scope/broker doubles; PostgreSQL and transactions are real. They do not certify provider or Azure connectivity.

Verification of the web/adapters increment on 2026-11-09: the README `clean verify` passed 236 tests with zero failures/errors/skips. This includes 29 real-listener HTTP checks with locally signed RSA JWTs, 54 protection/configuration checks, 3 SDK publisher checks with a mocked sender, the existing 49 real PostgreSQL persistence checks and a new full servlet-composition check using PostgreSQL 17.11. The 52 core sources compiled with an empty dependency classpath; `jdeps` reported `java.base` only. The packaged JAR excludes fixtures/test libraries. OpenAPI was generated and checked against the five implemented route paths/six operations. These are local checks: identity/provider/sender boundaries remain synthetic, hosted CI has not run, and no real GitHub → broker → consumer flow is certified.

Verification of private credential adapters on 2026-11-09: README `clean verify` passed 283 tests without failures/errors/skips, including 21 local HTTP OAuth tests and 26 mocked Key Vault checks. A subsequently added SDK-factory construction check also passed in the 27-test Key Vault scope, without calling the vault. Fifty-four framework-free core sources compiled with an empty classpath and `jdeps` reported `java.base` only; the JAR excludes test fixtures/libraries. The updated image built locally and still runs as `10001:10001`. Independent review closed strict trailing-JSON parsing findings. None of these checks establishes real GitHub/Key Vault access, an activated connection lifecycle or hosted CI execution.

PR #6 is integrated at `55cdf3fb59872e9546070a97ffa267d3c759c63c` and its hosted Verify check passed. The next local change adds a private one-shot refresh executor over those adapters. It verifies the connection identity before claiming, uses an exact-version SQL snapshot, marks possible remote execution durably and conditionally activates the saved replacement. It distinguishes definitive rejection from uncertainty and never retries an existing operation. It is not connected to HTTP, a scheduler or restored authorization. See the [refresh execution constraints](docs/deployment-contract.md#private-one-shot-refresh-execution).

The private [credential adapters](docs/deployment-contract.md#local-private-credential-adapters) implement exact-version Key Vault pair/PKCE custody and a single-attempt OAuth token transport. They require trusted constructor inputs and are not composed into the HTTP application. The one-shot refresh executor remains private; connection/browser activation, cleanup and live identity proof are still pending. A successful private custody call does not establish authorization.

Runtime inputs and the remaining admission/origin handoff are listed in the [deployment contract](docs/deployment-contract.md#local-adapter-configuration). No credential or production environment file is checked in. OpenAPI is generated from the HTTP implementation; it is disabled by default and, when explicitly enabled, requires the Pipeline caller/scope. No Swagger UI or callback endpoint is installed.

The [CI workflow](.github/workflows/ci.yml) checks contribution messages, runs Maven
with real PostgreSQL and builds/checks a non-root linux/amd64 image for PRs and
main pushes. Verification has read-only repository permissions. A separate job
publishes the exact checked main-push archive through shared Workspace automation;
PRs never publish. CI does not deploy or assign an RC/version per integration.
After packaging, the local image check is:

```bash
docker build --platform linux/amd64 --build-arg VCS_REF=working-tree --tag pipesor-ingestion:local .
GITHUB_SHA=working-tree python3 tools/check_image.py pipesor-ingestion:local
```

The image build passed locally. Inspection confirmed `10001:10001`; `docker run --rm --network none pipesor-ingestion:local` exited 1 for missing `server.port`, as expected with no runtime inputs. The pinned Temurin Java 25 image requires explicit runtime configuration; building an image does not activate any integration or approve an IaC/runtime handoff.

Framework independence can be checked after the build:

```bash
python3 - <<'PYCORE'
from pathlib import Path
import subprocess
root = Path("src/main/java/org/pipesor/ingestion")
sources = sorted(str(p) for part in ("github/domain", "github/application", "normalization/domain", "normalization/application", "capture/domain", "capture/application", "shared/domain") for p in (root / part).rglob("*.java"))
output = "target/core-verification"
subprocess.run(["javac", "--release", "25", "-classpath", "", "-d", output, *sources], check=True)
subprocess.run(["jdeps", "-s", output], check=True)
PYCORE
```

The local startup check is:

```bash
java -jar target/pipesor-ingestion-normalization-service-0.1.0-SNAPSHOT.jar
```

Without the required runtime inputs this exits 1; it supplies no embedded database, identity or port defaults. The full-application test runs the actual servlet composition with real PostgreSQL and synthetic identity configuration, optional integrations disabled and unauthenticated requests denied. Query configuration tests separately verify each missing query port. The normal composition still has no operational database-backed `CurrentGitHubLink`, real provider or admission adapter: a stored ACTIVE row is not proof of safe authorization after restore. `ddl-auto=validate`, automatic Flyway disabled and SQL initialization disabled in [application.yml](src/main/resources/application.yml) preserve the pending production migration/recovery handoff. There is no production connection or secret configuration supplied here.

A `BLOCKED` uncertain refresh retains its exact late confirmation and continues to prevent another refresh and final cleanup. Even confirmed cleanup results cannot terminalize that unresolved operation; a safe terminal reconciliation protocol/runner remains pending. Metadata status/progress is not completed remote revocation, erasure or shared functional audit. Browser callback binding, custody/provider runtime composition, restore/admission operating guarantees and ADR-011 audit integration remain gates before a live connection flow.

The [architecture boundary](docs/architecture.md#local-github-access-implementation-boundary) and [accepted coordinated design](docs/deployment-contract.md#connector-contract-proposal-for-decision) retain the split: Ingestion provides GitHub facts; Pipeline owns registration, capabilities and grants. [Docs domain decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md) owns DD-020 and the physical dictionary. DD-020 and DD-021 are integrated in Docs main at consulted revision `65792190d2ee32f0721d22848cd62cee1fd0d630`. Local implementation is not consumer integration or deployment evidence.

For documentation changes, check affected links and consistency with the referenced sources, then run:

```bash
git diff --check
```

For TOML configuration changes, run this syntax check with Python 3.11+ (the selected Python baseline supports `tomllib`):

```bash
python3 -c 'from pathlib import Path; import tomllib; [tomllib.loads(p.read_text()) for p in Path(".codex").rglob("*.toml")]; print("TOML syntax OK")'
```

Follow the organization [contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [code of conduct](https://github.com/pipesor-org/.github/blob/main/CODE_OF_CONDUCT.md) and [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md). Licensing remains pending.

## Optional Codex setup

Start Codex from this repository root in a trusted project so [repository instructions](AGENTS.md) and [project configuration](.codex/config.toml) are loaded. The [task-based workflow](AGENTS.md#task-based-agent-workflow) is a Ingestion-local pilot; shared backend conventions and other repositories are unchanged. Custom roles inherit permissions; a no-edit instruction is a behavioral constraint, not a separate read-only sandbox.

| Role | Model | Reasoning effort | Use |
|---|---|---|---|
| Primary coordinator | gpt-6.1-sol | medium | Scope, coordination, integration and completion. |
| [service_analyst](.codex/agents/service-analyst.toml) | gpt-6.1-sol | high | Ambiguous requirements, source/approval reconciliation and decision options. |
| Built-in worker | gpt-6.1-sol | high | Implementation and meaningful verification; uses project subagent defaults. |
| [ingestion_reviewer](.codex/agents/ingestion-reviewer.toml) | gpt-6.1-sol | high | Independent GitHub trust, privacy, publication and acceptance-evidence review. |

Keep the limit of two open spawned threads, excluding the primary. Use direct implementation for small clear fixes; do not add a researcher to every task. The built-in explorer remains available for narrow code questions and otherwise inherits project subagent defaults. For a straightforward bounded worker/explorer task, an explicit spawn setting may reduce effort to medium; higher effort requires concrete complexity, such as a disputed invariant or concurrency/security failure. Do not use max/ultra routinely. These are starting choices consistent with IaC, not measured latency or quality guarantees; use an available model if the configured one is unavailable and disclose any substitution.

The coordinator and built-in-role defaults are in .codex/config.toml. Custom role files explicitly pin model and effort and take precedence for those roles; change their files when revising the policy. Do not change global configuration, permissions or providers as part of a role adjustment. See the official [subagent guide](https://learn.chatgpt.com/docs/agent-configuration/subagents) for model/effort precedence and role discovery.

Example request:

> Refine this feature with service_analyst, preserve existing approvals and implement
> the authorized work with worker. Have ingestion_reviewer independently check the
> acceptance criteria and meaningful failure tests, resolve findings and report
> exact verification and remaining dependencies. Do not publish or deploy.

After changing instructions/configuration, start a fresh session. Parsing TOML alone does not establish live discovery. From this trusted repository, inspect model-visible role descriptions without invoking a model:

```bash
codex debug prompt-input 'List the active repository instructions, specialist roles and authorization boundaries.'
```

Check the configured model/effort and thread limit in the TOML files against the table, and confirm service_analyst/ingestion_reviewer descriptions are present alongside the built-in worker/explorer in the prompt diagnostic. Avoid publishing raw diagnostic output, which can include local context. These checks inspect configuration/discovery, not model availability or delegation behavior. For a live pilot in a fresh session, ask the analyst for a bounded no-edit brief and the reviewer to challenge it; then validate implementation handoffs on an authorized feature. See also the official [instruction-discovery guide](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

Setup verification (2026-11-06, codex-cli 0.167.0): TOML syntax, role fields, model/effort settings, thread limit and local documentation files/anchors passed validation. The prompt-input diagnostic loaded the repository workflow but did not expose the exact custom-role descriptions; no explicit trust entry was found for this project or its ancestors in the active user configuration. Custom-role discovery remains unverified pending a fresh trusted-project session. Global trust/permissions were not changed. No model invocation, live agent pilot, application tests or GitHub/Entra/Service Bus integration was performed.


Live workflow pilot (2026-11-06): `service_analyst`, built-in `worker` and `ingestion_reviewer` were discovered and invoked in this session. The custom roles expose `gpt-6.1-sol` / high; the worker was explicitly invoked with the same model/effort. No role, global permission or model configuration was changed. This establishes live delegation, not integration or service deployability. The earlier prompt-input discovery limitation above is historical.

Pilot assessment: research was reused and independent review caught unnecessary reapproval and batch-fact issues. However, the first reviews missed duplicated operation-to-administration rules and the omitted Spring composition; the user's architecture question exposed both and caused code, test and documentation rework. The correction removes the duplicated policy and adds external Boot composition. A subsequent convention check also corrected the flat domain package into `valueobjects`; no entity or aggregate lifecycle was invented. The pilot therefore has evidence of a requirements/ownership review gap, not certification of the whole architecture. Future handoffs must separate provider facts from consumer authorization and check conventions beyond domain independence. Remaining API/custody contracts still need their own decisions; these observations do not justify adoption across services.

Verification of private refresh execution on 2026-11-09: README `clean verify` passed 300 tests with no failures/errors/skips, including 16 new PostgreSQL 17.11 refresh checks. These cover tenant/subject isolation before claim, concurrent execution, remote/persistence failures, metadata activation failure after a saved candidate, expiry during marker I/O, disconnect/late acknowledgements and submicrosecond timestamp precision. The 57 core sources compiled with an empty classpath and `jdeps` reported `java.base` only. Independent review closed all findings. External OAuth/custody calls in these refresh checks are simulated; PostgreSQL, metadata transactions and claim exclusion are real. No runtime activation or provider connectivity is certified.

Verification of the private GitHub read provider on 2026-11-09: README `clean verify` passed 337 tests with no failures/errors/skips, including 37 new local HTTP provider checks. They cover bound user/generation credentials, effective user/App membership, admin true/false, huge IDs, null branches, suspension and crossings, complete pagination/continuation validation, duplicate/trailing JSON, SSO partial results, ambiguous 403/rate limits, successful last-quota responses, repository-name replacement, expiry, aggregate byte/time limits and private diagnostics. The full application check also confirms that no `ReadOnlyGitHub` bean is silently composed. Independent review closed all findings. HTTP fixtures are real local servers; GitHub authorization data and credentials are synthetic. No live provider, operational credential reader, admission or restore integration is certified.

Verification of durable publication execution on 2026-11-09: README `clean verify` passed 367 tests without failures/errors/skips, including 12 new PostgreSQL 17.11 worker checks, 3 runner checks and 15 configuration checks. After strengthening configuration-cause assertions, the 15-test configuration scope passed again. The 59 core sources compiled with an empty classpath and `jdeps` reported `java.base` only. Tests cover due selection and aliases, concurrent claims, frozen-output retries, scope failure/fairness, partial plans, cancellation with interruption-ignoring I/O, managed shutdown and fatal-runner termination. PostgreSQL and transactions are real; broker/scope boundaries are synthetic. Independent review findings were resolved. No actual Service Bus or authorized scope source was connected, and the worker remains disabled by default.

Verification on 2026-11-10: README `clean verify` passed 515 tests with zero failures/errors/skips. The final build's Java/resource/pom fingerprints match the current source. Tests include real PostgreSQL webhook acceptance, duplicate/conflicting delivery and concurrency checks; real local HTTP/HMAC/JWT/OpenAPI and deadline/capacity checks; filesystem authority and RSA signing; and simulated Key Vault/CDN boundaries. Sixty core sources compiled with an empty dependency classpath and `jdeps` reported only `java.base`. The packaged JAR excludes test fixtures/libraries; independent review closed the findings. GitHub App, Azure resources and an installation do not exist for this pilot, so no live end-to-end result is claimed. CI was explicitly deferred and was not executed, diagnosed or modified.

Verification of approved Verify preparation on 2026-11-10: README `clean verify` passed 541 tests with zero failures/errors/skips. After removing an unused version constant and adding preamble-failure regression assertions, the 9-test profile scope passed again through `verify`, repackaging the final source. Sixty-two core sources compiled with an empty classpath; `jdeps` reported only `java.base`. PostgreSQL 17.11 tests exercise claim-before-read, protected atomic preparation, reserved IDs/frozen bytes through publication, crossed receipt rejection, expiry, revoked scope and oversized wire refusal. Archive HTTP/CDN and GitHub source data remain synthetic; no App/Azure integration or CI was run. The packaged JAR excludes test fixtures/libraries; affected relative file links and whitespace checks passed. Independent review has no material findings left. Automatic preparation dispatch and live environment validation remain pending.

## CI/CD

[CI](.github/workflows/ci.yml) calls the shared [`service-ci.yml`](https://github.com/pipesor-org/pipesor-ci#catalog)
from [pipesor-ci](https://github.com/pipesor-org/pipesor-ci), pinned by commit SHA (Dependabot proposes updates):

1. **Conventions**: canonical commitlint/Lefthook configuration, PR title (at most 65 characters) and description,
   Conventional Commits for the PR range and for the integrated `main` commit, actionlint and zizmor.
2. **Build**: the tooling tests and `./mvnw -B -ntp clean verify`, then one `linux/amd64` image build tagged
   `ghcr.io/pipesor-org/pipesor-ingestion-normalization-service:sha-<commit>-<run>-<attempt>`, the shared OCI revision/non-root checks and
   `tools/check_image.py`.
3. **Image assurance**: Trivy SBOM and vulnerability report (90-day evidence); fixable HIGH/CRITICAL vulnerabilities
   block. Reviewed exceptions go in `.trivyignore.yaml`.
4. **Publish** (pushes to `main` only): the exact tested archive is pushed to the private GHCR package without
   rebuilding and `candidate-<run>-<attempt>/candidate.json` records the source SHA, image, digest and CI run/attempt.
   Use **Re-run all jobs** after a publication failure; tags and artifacts include the attempt.

PRs are verified and scanned but never published. A ruleset protects `main`: changes arrive through squash-merged PRs.

The image check (`tools/check_image.py`) runs without credentials or external networking: it verifies the non-root user and OCI revision label and requires the service to refuse to start without its required `pipesor.*` platform settings.

Local checks before opening a PR (Java 25, Python 3, Bun 1.4.4, Docker):

```bash
bun install --frozen-lockfile && bun run setup:hooks
python3 -m unittest discover -s tools -p 'test_*.py'
./mvnw -B -ntp clean verify
revision="$(git rev-parse HEAD)"
docker build --platform linux/amd64 --build-arg "VCS_REF=$revision" --tag pipesor-ingestion-normalization-service:local-ci .
GITHUB_SHA="$revision" python3 tools/check_image.py pipesor-ingestion-normalization-service:local-ci
```

**Deployment.** This repository does not deploy. The environment's Terraform root in
[pipesor-iac](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/dev-runtime-deployment.md) pins this service's
image by digest. Promote a candidate by using its `candidate.json` digest in the next environment's reviewed plan;
roll back with a new reviewed plan for the earlier digest.

**Releases.** [Release candidate](.github/workflows/release.yml) is manual and hard-disabled in source. It uses the
shared [release action](https://github.com/pipesor-org/pipesor-ci/tree/main/actions/release) to name an existing
candidate digest `vX.Y.Z[-rc.N]` with a GitHub Release, never rebuilding; stable requires retained preprod validation
for the same digest. See the [shared CI/CD guide](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/cicd.md).

## Runtime configuration

Names follow the [runtime manifest](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/runtime-manifest.md); `pipesor.*` settings bind through Spring relaxed binding (`PIPESOR_GITHUB_APP_ID` → `pipesor.github.app-id`). Typed properties: [platform](src/main/java/org/pipesor/ingestion/shared/security/PlatformSecurityProperties.java), [session](src/main/java/org/pipesor/ingestion/configuration/SessionProperties.java), [GitHub](src/main/java/org/pipesor/ingestion/configuration/GitHubProperties.java), [capture](src/main/java/org/pipesor/ingestion/configuration/CaptureProperties.java), [Service Bus](src/main/java/org/pipesor/ingestion/configuration/ServiceBusProperties.java), [API limits](src/main/java/org/pipesor/ingestion/configuration/ApiProperties.java); bounded defaults live in [application.yml](src/main/resources/application.yml). A missing or contradictory value fails startup; file-based material under `azure` fails startup.

| Variable | `local` | `azure` |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `azure` |
| `PIPESOR_TENANT_ID`, `PIPESOR_WORKLOAD_ID=ingestion`, `PIPESOR_SECURITY_AUDIENCE=urn:pipesor:api:ingestion` | required | required |
| `PIPESOR_SIGNING_KEY_FILE` / `PIPESOR_SIGNING_KEY_ID` | PEM (0600) | Key Vault key `pipesor-ingestion-signing` |
| `PIPESOR_SESSION_SIGNING_KEY_FILE` / `PIPESOR_SESSION_SIGNING_KEY_ID` | PEM (0600) | Key Vault key `pipesor-session-signing` |
| `PIPESOR_SECURITY_TRUSTEDWORKLOADS_0_ID=pipeline`, `_JWKSURI`, `_ROLES=Pipeline.MachineScope.Check,Pipeline.GitHubFacts.Read` | loopback HTTP | HTTPS |
| `PIPESOR_SECURITY_SESSION_JWKSURI` (this service's own JWKS) | `http://127.0.0.1:8081/.well-known/jwks.json` | `https://ingestion.<env-domain>/.well-known/jwks.json` |
| `PIPESOR_DASHBOARD_ORIGIN` | `http://127.0.0.1:5173` | dashboard origin |
| `PIPESOR_DATABASE_URL`, `_USERNAME`, `_PASSWORD` | password auth | URL only (Entra token plugin appended) |
| `PIPESOR_SERVICE_BUS_CONNECTION_STRING` / `PIPESOR_SERVICE_BUS_NAMESPACE`, `PIPESOR_SERVICE_BUS_TOPIC` (default `workflow-evidence`) | emulator connection string | namespace + `DefaultAzureCredential` |
| `PIPESOR_GITHUB_APP_ID`, `PIPESOR_GITHUB_CAPTURE_DOMAIN` | required | required |
| `PIPESOR_GITHUB_AUTHORITY_FILE` | only for the admission import command | only for the admission import command |
| `PIPESOR_GITHUB_CALLBACK_URL` | default `http://127.0.0.1:8081/api/v1/auth/github/callback` | `https://ingestion.<env-domain>/api/v1/auth/github/callback` |
| `PIPESOR_GITHUB_CLIENT_ID` | optional (else `GET /app` with the App JWT) | optional |
| `PIPESOR_GITHUB_PRIVATE_KEY_FILE`, `PIPESOR_GITHUB_PRIVATE_KEY_SHA256`, `PIPESOR_GITHUB_CLIENT_SECRET_FILE` | operator files (0600) | — |
| `PIPESOR_GITHUB_KEY_VAULT_URL`, `PIPESOR_GITHUB_PRIVATE_KEY_SECRET_VERSION`, `PIPESOR_GITHUB_CLIENT_SECRET_VERSION` (names default to `github-app-private-key`, `github-app-client-secret`) | — | exact versions |
| `PIPESOR_GITHUB_CUSTODY_DIRECTORY` / `PIPESOR_GITHUB_CUSTODY_VAULT_URL` | default `~/.config/pipesor-local/custody` (0700/0600) | OAuth custody vault |
| `PIPESOR_GITHUB_API_BASE_URL`, `PIPESOR_GITHUB_WEB_BASE_URL` | default `https://api.github.com`, `https://github.com` | same |
| `PIPESOR_GITHUB_WEBHOOK_ENABLED` (+ `PIPESOR_GITHUB_WEBHOOK_SECRET_FILE` or `_SECRET_VERSION`) | default `false` | optional |

Flyway migrates schema `ingestion` at startup (`ddl-auto: validate`). Actuator exposes only `/actuator/health/liveness` and `/actuator/health/readiness` (readiness includes the database).

## Routes

| Route | Caller |
|---|---|
| `GET /actuator/health/liveness`, `GET /actuator/health/readiness`, `GET /.well-known/jwks.json` (workload and session keys) | unauthenticated |
| `GET /api/v1/auth/github/start?return_to=`, `GET /api/v1/auth/github/callback`, `POST /api/v1/auth/session` | unauthenticated (login flow) |
| `POST /api/v1/auth/logout`, `/api/v1/github/connection`, `/api/v1/github/repositories`, `…/{repositoryId}/workflows`, `…/workflows/{workflowId}/runs?conclusion=failure`, `POST`/`GET …/runs/{runId}/attempts/{attempt}/analysis` | session token, scope `GitHub.Self` |
| `POST /internal/v1/github/access-verifications`, `POST /internal/v1/github/workflow-verifications` | `pipeline` workload, `Pipeline.GitHubFacts.Read`, plus `X-Pipesor-Subject-Token` |
| `POST /internal/v1/capture/machine-scope-checks` | `pipeline` workload, `Pipeline.MachineScope.Check` |
| `PUT /internal/v1/capture/workflow-scopes/{repositoryId}/{workflowId}` | `pipeline` workload, `Pipeline.CaptureScope.Write` (ADR-023) |
| `POST /webhooks/v1/github/workflow-runs` | GitHub HMAC (only with `PIPESOR_GITHUB_WEBHOOK_ENABLED=true`) |
| `GET /v3/api-docs` | `local` profile only |

Capture authority (ADR-023): admissions and workflow scopes live in `ingestion.repository_admissions` and
`ingestion.capture_workflow_scopes`; the authority file is not read at runtime. Pipeline sets each registered
workflow's status (`ACTIVE`/`PAUSED`) through the workflow-scopes route; a status change increments the scope
revision and revokes the previous one. The analysis request answers `409 CAPTURE_SCOPE_PENDING` while a workflow has
no active scope. Admissions are an operator act, imported from a reviewed authority file with:

```bash
java -jar target/pipesor-ingestion-normalization-service-*.jar --spring.main.web-application-type=none \
  --pipesor.admin.import-authority=$HOME/.config/pipesor-local/authority.json
```

The import is an idempotent upsert; existing machine scopes keep their reviewed scope revision so evidence captured
earlier stays verifiable.

Login: `start` creates a one-time attempt (state digest, PKCE S256 verifier in custody, 10 min) and redirects to GitHub. The `callback` consumes it once, exchanges the code, verifies the GitHub user (any GitHub user who authorizes the App may sign in, ADR-025; repository access is checked per repository on every read and command, and repositories are admitted per ADR-024), stores the user token pair in custody through the ADR-020 connection model and redirects to `<dashboard>/auth/complete?handoff=<one-time value>` (60 s) or `?error=<invalid_state|access_denied|unavailable>`. `POST /api/v1/auth/session` returns `{accessToken, expiresAt, user: {githubUserId, login}, returnTo}`; the session lasts at most one hour. The analysis status returns Pipeline's deterministic `executionId`/`incidentId` once published.

## Local login walkthrough

1. GitHub App settings (owner action): add the callback URL `http://127.0.0.1:8081/api/v1/auth/github/callback`, keep "Expire user authorization tokens" enabled and generate a client secret. Save the secret as an owner-only file, e.g. `~/.config/pipesor-local/github-client-secret` (mode 0600).
2. Start the local stack (`pipesor-workspace`), then run Ingestion with the generated env file plus the GitHub settings:

   ```bash
   set -a; . ~/.config/pipesor-local/env/ingestion.env
   PIPESOR_GITHUB_APP_ID=<app id> PIPESOR_GITHUB_PRIVATE_KEY_FILE=<pem> PIPESOR_GITHUB_PRIVATE_KEY_SHA256=<sha256>
   PIPESOR_GITHUB_CLIENT_SECRET_FILE=$HOME/.config/pipesor-local/github-client-secret
   PIPESOR_GITHUB_AUTHORITY_FILE=$HOME/.config/pipesor-local/authority.json PIPESOR_GITHUB_CAPTURE_DOMAIN=<domain>
   set +a; ./mvnw -B -ntp spring-boot:run
   ```

3. Check `curl http://127.0.0.1:8081/actuator/health/readiness` and `curl http://127.0.0.1:8081/.well-known/jwks.json` (two keys).
4. Open the dashboard at `http://127.0.0.1:5173` and log in; it calls `GET /api/v1/auth/github/start`, GitHub redirects back to the callback, and the dashboard exchanges the handoff at `POST /api/v1/auth/session`. Tokens, codes and handoff values are never logged.

The user's GitHub token is refreshed server-side when it is within five minutes of expiry; logout invalidates the link generation (custody cleanup operations stay pending, as in DD-020).
