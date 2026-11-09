# pipesor-ingestion-normalization-service

Ingestion and Normalization Service captures authorized workflow evidence, protects sensitive data and publishes normalized events with provenance and `correlationId` to Azure Service Bus. Its initial source is GitHub Actions. Its canonical architecture ID is `ingestion_normalization_service`; the planned runtime is Java / Spring Boot on Azure Container Apps.

The [approved ADR-020 evolution](docs/architecture.md#approved-github-access-evolution) also assigns GitHub user connection, discovery and verified access/metadata to this service; DD-020 additionally records the locally approved metadata/custody design. Runtime identity, callback, admission, recovery and delivery inputs remain distinct from that approval.

This repository contains a Java 25 / Spring Boot application with a non-web composition root, GitHub facts/discovery, PostgreSQL connector metadata, normalized message preparation and an owned durable capture/publication ledger. Ingestion reports current admitted/readable/administrator facts; Pipeline owns registration, grants and the decision about AIOps capabilities. HTTP, real GitHub/Entra adapters, credential custody, live capture/broker publication, container images and release automation remain unimplemented.

## Documentation

- [Documentation baseline](docs/documentation-baseline.md): audited source revisions, evidence limits and adoption after merge.

- [Architecture](docs/architecture.md): service boundaries, integration context and current sources in `pipesor-docs`.
- [Deployment contract](docs/deployment-contract.md): development baseline, delivery requirements and unresolved contracts.
- [Cross-service contracts](docs/deployment-contract.md#cross-service-contract-proposals--2026-11-08): locally approved execution/evidence, routing and connector design; consumer adaptation and concrete capture/browser/runtime inputs remain pending.
- [AGENTS.md](AGENTS.md): repository instructions for coding agents.

## Development and verification

The [development baseline](docs/deployment-contract.md#development-baseline) records the selected toolchain and dependency policy. The [entry point](src/main/java/org/pipesor/ingestion/IngestionServiceApplication.java), [query configuration](src/main/java/org/pipesor/ingestion/configuration/GitHubConfiguration.java) and [metadata configuration](src/main/java/org/pipesor/ingestion/configuration/GitHubPersistenceConfiguration.java) compose the application without an HTTP listener. Domain/application and shared domain identity remain independent of Spring, HTTP and ORM.

Metadata commands prepare a connection generation and OAuth attempt, record protected PKCE/version confirmations, consume attempts once, activate with revision guards, record refresh phases and invalidate with durable cleanup intent. [JPA persistence](src/main/java/org/pipesor/ingestion/github/infrastructure/persistence/jpa/JpaConnectionRepository.java) loads the current/target state and nonterminal guards rather than the full history; an infrastructure transaction wraps each complete local command. [V1](src/main/resources/db/migration/V1__github_connection_metadata.sql) owns only the `ingestion` schema and derives from canonical domain 1.2.0 / DD-020. It creates no roles, grants or downstream tables.

The [normalization handler](src/main/java/org/pipesor/ingestion/normalization/application/commandhandlers/NormalizeObservationHandler.java) prepares a complete in-memory output plan from a whitelisted internal observation, independently supplied expected capture scope and caller-reserved message IDs. It rejects scope mismatches before protection. The [mandatory protection port](src/main/java/org/pipesor/ingestion/normalization/application/ports/EvidenceProtection.java) must inspect/mask/minimize free text and may refuse processing; no production sanitizer or pass-through adapter is supplied. Correlation and scope revision must remain exact or fail. Validation is not authentication: a future trusted source adapter must verify the actual GitHub run/attempt and current scope.

The [message transformer](src/main/java/org/pipesor/ingestion/normalization/interfaces/messaging/transform/NormalizedMessagesTransformer.java) accepts only a result created after protection and produces Pipeline `execution.snapshot` v1 and, for failed/timed_out attempts, protected evidence v2 with coherent broker IDs/routing and defensive JSON bytes. Failed attempts require classified evidence; success/nonterminal attempts produce only a snapshot. Jackson 3.1.7 is selected by the Boot BOM and is confined to the wire adapter. The normalizer prepares immutable in-memory values; the capture ledger below owns ID reservations and freezes outputs for retries. A future source adapter must use those reserved IDs and cannot treat a standalone normalization call as durable acceptance.

The [capture handler](src/main/java/org/pipesor/ingestion/capture/application/commandhandlers/CaptureHandler.java) and [JPA ledger](src/main/java/org/pipesor/ingestion/capture/infrastructure/persistence/jpa/JpaCaptureLedger.java) now provide explicit acceptance, preparation and per-output publication operations. Acceptance commits the receipt, delivery alias and reserved message UUIDs; preparation freezes protected observations and the complete one/two-output plan. Short transactions, leases/fences and a common lock order protect concurrent workers. Publication calls the required current-machine-scope and broker ports outside SQL transactions, then records confirmation. The production ports have no successful default adapter and no worker/scheduler or webhook is activated. The API accepts trusted internal source metadata; a constructor or supplied digest does not authenticate GitHub.

[V2](src/main/resources/db/migration/V2__capture_publication_ledger.sql) derives from locally adopted Docs domain 1.3.0 / DD-021 and adds four owner-local tables without changing V1. Retries read frozen IDs/bytes rather than rerun normalization; an uncertain acknowledgement may cause duplicate delivery under the existing consumer contracts. Only confirmed bodies past the explicitly supplied recovery horizon can be removed; receipt/dedup metadata and blocked/pending work are retained. No retention duration, metadata purge policy or live deployment input is inferred. Original source nanoseconds are preserved using microsecond timestamp columns plus submicrosecond companions.

Requires Java 25 and Docker for the PostgreSQL integration checks. The wrapper pins Maven 3.10.0 with a distribution SHA-256; Boot 4.1.2 and compatible dependencies are pinned in [pom.xml](pom.xml). Verified build command with local caches:

```bash
MAVEN_USER_HOME=/tmp/pipesor-ingestion-maven ./mvnw -B -ntp -Dmaven.repo.local=/tmp/pipesor-ingestion-m2 clean verify
```

This compiles, runs isolated behavior/composition and real PostgreSQL tests, and packages the Boot JAR. Testcontainers uses PostgreSQL 17.11; tests explicitly run Flyway and validate JPA mappings. They exercise uniqueness/FK/partial/deferred constraints, concurrent activation/claims, expiry/replay, fencing, rollback, generation isolation, late acknowledgements and fixed failure causes without private references. Only test sources supply GitHub/link/admission doubles and synthetic custody confirmations; no GitHub, Entra or Key Vault is contacted. Docker absence is a test failure, not a successful skipped integration check. With writable normal Maven caches, the equivalent command is `./mvnw -B -ntp clean verify`; the reported checks use the explicit cache paths above.

Verification on 2026-11-07: the wrapper command passed all 96 tests (64 query behavior, 4 isolated composition, 25 PostgreSQL metadata and 3 exact-version checks), with no failures, errors or skips, and packaged the Boot JAR. The coordinated canonical Docs changes also passed generation, 106 unit tests and 9 SQL checks on PostgreSQL 16.15 with pgvector 0.8.7. These are local checks; the different database versions do not establish production compatibility or live provider integration.

Verification on 2026-11-08: the same wrapper `clean verify` passed 122 tests with no failures/errors/skips, including 26 normalization checks and the existing 25 PostgreSQL 17.11 metadata checks. Forty domain/application/shared-domain sources compiled with an empty dependency classpath; `jdeps` reported `java.base` only. An isolated compilation of Pipeline's actual snapshot transformer/domain sources accepted both generated snapshots (SHA40/SHA64, distinct attempts and huge textual IDs), with matching owner UUIDv5 rules and nanosecond timestamps. This checks decoder compatibility, not Pipeline's database processing or live messaging. NLP's current v1 domain parser rejected both generated evidence v2 bodies with `unsupported_schema_version`, confirming its adaptation gate. The packaged JAR includes Jackson and excludes fixtures, tests and the externally compiled consumer classes.

Verification on 2026-11-09: wrapper `clean verify` passed 149 tests with no failures/errors/skips, including 24 real PostgreSQL 17.11 capture tests, 25 connector-persistence tests and 3 fingerprint checks. The final full build includes exact SQLState/constraint assertions and regressions for retry deadlines elapsed during I/O and late scope denial after claim takeover. The 51 core sources compiled with an empty classpath and `jdeps` reported only `java.base`. Actual Docs generation, 109 unit tests and 14 PostgreSQL 16.15/pgvector 0.8.7 checks passed. V2 matches the actual canonical SQL export and lifecycle guards byte-for-byte. These checks use synthetic source/protection/scope/broker doubles; PostgreSQL and transactions are real. They do not certify provider or Azure connectivity.

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

Without database configuration this exits 1 for an unconfigured DataSource, with no embedded database or web server fallback. Query configuration tests separately verify each missing query port. The normal composition still has no operational database-backed `CurrentGitHubLink`, real provider or admission adapter: a stored ACTIVE row is not proof of safe authorization after restore. `ddl-auto=validate`, automatic Flyway disabled and SQL initialization disabled in [application.yml](src/main/resources/application.yml) preserve the pending production migration/recovery handoff. There is no production connection or secret configuration supplied here.

A `BLOCKED` uncertain refresh retains its exact late confirmation and continues to prevent another refresh and final cleanup. Even confirmed cleanup results cannot terminalize that unresolved operation; a safe terminal reconciliation protocol/runner remains pending. Metadata status/progress is not completed remote revocation, erasure or shared functional audit. HTTP/callback binding, actual custody/provider access, restore/admission operating guarantees and ADR-011 audit integration remain gates before a live connection flow.

The [architecture boundary](docs/architecture.md#local-github-access-implementation-boundary) and [accepted coordinated design](docs/deployment-contract.md#connector-contract-proposal-for-decision) retain the split: Ingestion provides GitHub facts; Pipeline owns registration, capabilities and grants. [Docs domain decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md) owns DD-020 and the physical dictionary. Local acceptance and implementation are not publication, integration or deployment evidence.

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
