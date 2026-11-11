# Architecture consumption

Consulted on 2026-11-06: `pipesor-org/pipesor-docs` full Git SHA **`c84ef3e4629fcb0c38315f32b641a5fb949e450d`**, using the clean local checkout (no uncommitted source overlay). Canonical model: `ey_aiops_ci_cd_diagnosis`, version `0.5.0-mvp-iac-alignment`, status `draft`. `ingestion_normalization_service` is a confirmed container with an independent repository under ADR-018; confirmed is a model status only.

Architecture source links below follow `main`. The summary reflects the consulted revision recorded above; review it against subsequent accepted decisions before implementation.

See the [documentation baseline](documentation-baseline.md) for the remote reconciliation and adoption limits of this update. Source SHAs are evidence identifiers, not frozen navigation links.

## Sources by subject

- [Source instructions](https://github.com/pipesor-org/pipesor-docs/blob/main/AGENTS.md), [architecture model](https://github.com/pipesor-org/pipesor-docs/blob/main/model/architecture.yml): `elements[ingestion_normalization_service]`, `relationships`, `deployment.container_hosts.ingestion_normalization_service`, `architectural_invariants` (INV-001/002/003/005/006/007/008/009/010), CTRL-001–006, `audit_trail`, `retention`.
- [Persistent model](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain.yml), [domain decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md): table ownership, assumptions, retention and open questions. Domain owners are listed in the delivery contract; no derived SQL/DBML is consumed as independent authority.
- Applicable ADRs: [ADR-001](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-001-azure-container-apps.md), [ADR-002](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-002-azure-observability.md), [ADR-013](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-013-retention-365-days.md), [ADR-018](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-018-application-architecture-ddd-cqrs.md), [ADR-005](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-005-github-app-authentication.md). [ADR-019](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-019-align-mvp-with-iac.md) supersedes the affected historical APIM/Blob/foundation statements. For current decisions, read the [decision index](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/index.md) for subsequent supersession.
- [Proposed design](https://github.com/pipesor-org/pipesor-docs/blob/main/model/design.yml), [design rationale](https://github.com/pipesor-org/pipesor-docs/blob/main/model/design_decisions.md): retain proposed states and DCQ questions; not approved classes or API contracts.
- [Source provenance](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/sources/README.md), [reconciliation](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/reconciliation.md), [committed pending register](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md). Charter controls scope; architecture/ADRs control boundaries; drift/applicable technology ADRs control technology. Functional research is derived and its API v0.1 is proposed.

## Current MVP alignment

[ADR-019](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-019-align-mvp-with-iac.md) incorporates the accepted IaC direction from `pipesor-iac@4b42c0c8e66578dfb890ae1cca8c9c31c774026e`: [native ingress](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-003-mvp-native-ingress.md), [resource baseline](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/mvp-resource-baseline.md) and [dev preparation](https://github.com/pipesor-org/pipesor-iac/blob/main/docs/adr-004-mvp-state-and-deployment.md).

- Use ACA native ingress for authorized dashboard/workflow calls. APIM and replacement gateways are excluded from the MVP. Internal services remain internal; Entra authentication, service authorization, scopes and trace/audit propagation still require implementation and contracts.
- Exclude workload Blob. GitHub Actions remains the original log source; PostgreSQL holds sanitized evidence, metadata, excerpts and knowledge. Preserve provenance and generic references without inventing replacement locators or duplicating complete logs. Independent recovery and 365-day retention remain unresolved (PD-017/030/031). Terraform state Blob is separate.
- Use the Microsoft Foundry AIServices account/project foundation with public HTTPS and Entra-only authentication. Preserve the logical inference capability and PostgreSQL/pgvector ownership. Application endpoint/SDK, models, embeddings/dimension, geography, grants and corpus remain pending (PD-005/006/023/026/027).
- Terraform retains four isolated environment roots/states; only `dev` is selected for the temporary academic MVP. Services must be implemented, integrated locally and validated before separately authorized Azure actions. IaC preparation is not deployment evidence; future promotion rules remain applicable outside the dev exception.

PD-033–036 and the stage-5 report are committed at the consulted revision. The old uncommitted overlay description is superseded. The pending register identifies Jhon Galvez and Ramiro Guzman as contract approval authority; approval count, GitHub enforcement and actual delivery-contract approvals remain unspecified.

## Repository boundary inventory

ADR-018 specifies multirepo per deployable microservice. Repository names below apply the existing `pipesor-` prefix and kebab-case to canonical IDs; IDs are unchanged.

| Canonical container | Independent backend repository |
|---|---|
| `ingestion_normalization_service` | `pipesor-ingestion-normalization-service` |
| `pipeline_service` | `pipesor-pipeline-service` |
| `log_event_service` | `pipesor-log-event-service` |
| `evidence_correlation_engine` | `pipesor-evidence-correlation-engine` |
| `recommendation_service` | `pipesor-recommendation-service` |
| `nlp_log_processor` | `pipesor-nlp-log-processor` |
| `rag_retrieval` | `pipesor-rag-retrieval` |

`web_dashboard` already has `pipesor-frontend`. Managed Service Bus, PostgreSQL/pgvector, Entra ID and Microsoft Foundry are not custom backend service repositories. IaC already has `pipesor-iac`. `pretrained_llm_diagnostic_service` is an external managed service despite its ID (ADR-015). The diagnostic API, orchestrator, output composer and human review are internal to `recommendation_service` (ADR-016/017). Audit is cross-cutting (ADR-011); reporting was removed (ADR-009), detector/LogBERT are outside MVP (ADR-010), notifications are excluded (ADR-012), and direct scanner integrations are outside MVP (ADR-014). No repository is created for those capabilities.

No ambiguity blocks the seven service boundaries. Operational gaps remain: embedding access paths are not separately declared for NLP/RAG despite the documented provider, and the owners/mechanisms for configuration, migrations and promotion are incomplete. Those gaps do not authorize new relationships or services.

## Service relationships

Read these canonical IDs in `model/architecture.yml`; internal C4 projections do not add deployment boundaries:

- `rel_github_actions_ingestion_event`: `github_actions` → `ingestion_normalization_service` (HTTPS/Webhook).
- `rel_ingestion_github_actions_read`: `ingestion_normalization_service` → `github_actions` (GitHub API/HTTPS).
- `rel_ingestion_service_bus`: `ingestion_normalization_service` → `azure_service_bus` (Azure Service Bus).
- `rel_observability_sources_ingestion`: `observability_sources` → `ingestion_normalization_service` (REST/HTTPS).

## Organization conventions consulted

Shared policy checkout/remote `main`: `pipesor-org/.github@36691787bb9d0c9fd6b78c76bfe82adffa0c1c87`; [contribution policy](https://github.com/pipesor-org/.github/blob/main/CONTRIBUTING.md), [security policy](https://github.com/pipesor-org/.github/blob/main/SECURITY.md), [PR template](https://github.com/pipesor-org/.github/blob/main/.github/PULL_REQUEST_TEMPLATE.md). Reuse organization defaults by reference, without duplicated community files or an unapproved license. Contribution rules remain owned by that policy; the baseline audit verified that the service repository is private, uses `main` and permits squash merges only. Existing Bun/Lefthook tooling belongs to those repositories; no application/toolchain dependency is introduced here.

Missing source approvals/provenance are tracked in PD-012–024, including individual ADR approval records (PD-014); preserve Accepted status while reporting those limits. Unresolved decisions relevant to this service: [PD-035](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-032](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-027](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-008](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-015](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md), [PD-033](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/pending-decisions.md). Scope them to the affected integration or deployment rather than blocking repository initialization.

## GitHub enforcement limitation

The baseline audit confirmed private visibility, `main` as default branch and squash-only merge settings. The GitHub rulesets API returned HTTP 403 with a plan-upgrade requirement for this service; no enforced branch protection or independent approval gate is certified. PR-title/body squash formatting and automatic branch deletion were historical initialization settings, not independently revalidated by this audit. Mandatory reviewed PRs and no direct pushes to main remain contribution rules; server-side enforcement has not been established. Keep these repositories private; do not change visibility to work around that limitation. No empty CI checks or deployment workflows are added.

## Approved GitHub access evolution

Source revalidated on 2026-11-06: pipesor-docs remote main at `18dacfbfcee22a92bb98dba85a378b023e96ac70`, plus its locally approved [ADR-020](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-020-github-user-access-and-workflow-onboarding.md) evolution committed at `af9e4d696fd8f518cdf4b16512ad8988307da2e6` on open Docs PR #8. At that revalidation the source checkout was clean; ADR-020 is committed on the proposal branch, not integrated main. The subsequent coordinated physical draft described below is a separate local, uncommitted documentation change and is not attributed to that PR head. The earlier consumption record above remains historical. The present task authorizes local implementation and verification, without publication or deployment. Backend and refined Java/Spring conventions are integrated at `18dacfb`; no newer approved executable connector contract was found in the consulted sources.

- Entra authenticates AIOps users. Ingestion owns GitHub linkage, user access-token lifecycle, discovery and verified repository/workflow/access facts. Installation tokens remain for machine capture; they do not substitute for lost user authorization. Keep GitHub interactions read-only.
- Verify effective user AND App access, tenant-admitted account/installation and required SSO; repository administration applies where the approved operation requires it. Callback authorization must recover the Entra-bound attempt, use state/PKCE and prevent replay; browser identity/permission flags are not proof.
- Pipeline owns Repository/Pipeline registration, operations and AIOps grants. It consumes verified facts without GitHub tokens. Ingestion must not write ops or decide Pipeline capabilities. Dashboard connection/discovery and internal Pipeline verification are distinct authenticated surfaces.
- The internal contract must bind actor/subject, tenant, operation, repository and installation, with current validity and explicit denied/disconnected/unavailable behavior. Routes, schemas, audiences/scopes, verifiable subject propagation, timeouts and versioning remain PD-034/035. No wire contract is selected here, and Pipeline's application port is not an approved HTTP payload.
- Logical linkage/credential ownership does not approve a database schema, secret store, encryption/key management, migration or credential retention/deletion mechanism. The user subsequently selected Azure Key Vault locally for PD-027; its usage mode, physical schema, operators and lifecycle contract remain pending before persistence. This does not authorize provisioning or change the neighboring source repository; do not reuse ops/evidence/audit to store tokens or create users/roles/teams.
- Preserve sanitization before publication, canonical context/provenance and agreed event semantics. No automatic backfill, workflow mutation or inferred change to in-flight event acceptance. User unlink does not by itself stop shared machine monitoring while the installation remains authorized.
- Log/Event and Recommendation must prevent human evidence/diagnosis/review APIs from bypassing repository restrictions under an agreed authorization context. These are external-owner dependencies; this repository change does not modify those services or approve cloud resources.

The [task-based agent workflow](../AGENTS.md#task-based-agent-workflow) uses a bounded analyst brief, implementation worker and independent ingestion reviewer. The first analysis should reconcile historical guidance against this approved evolution, retain decision provenance and isolate genuinely blocked work. Agent configuration alone certifies no application or integration. The local implementation and its limits are recorded below.


## Local GitHub access implementation boundary

The first executable increment implements human access verification, repository/workflow discovery and verified workflow selection in Java domain/application code. Ports isolate current linkage, tenant installation/account admission and read-only GitHub observations. Production adapters and an exposed server are absent: application inputs must originate from a future authenticated adapter and are not proof of Entra identity merely because their fields are populated. Test doubles are not installed in a production composition.

Each query binds actor, subject, tenant, operation, selection and correlation context. The provider must first resolve the actual user-token GitHub identity and match it to the stable linked identity; installation credentials cannot stand in for that check. User and App access, active installation, tenant admission and required SSO are cumulative conditions for disclosing metadata. Verification returns admitted/readable/administrator facts; operation labels bind observations but do not select AIOps permission rules. Workflow selection returns the observed administration fact, including false for an admitted reader. Pipeline alone decides whether those facts satisfy registration, settings or grant policy. Batch access results preserve readable and denied targets without returning repository names; definitive per-target denial does not abort other targets, while disconnected or unavailable dependencies abort the whole query. Discovery exposes metadata only after eligibility checks; dependency failure is not a successful empty enumeration. The final link check includes an opaque logical connection revision, so relinking the same GitHub user with unchanged metadata timestamps cannot combine observations from different connection generations. This defines a port requirement, not a physical storage mechanism or distributed atomicity.

Fresh observations are required for each invocation, with no positive cache across queries. Observation time is checked against the local call interval and is distinct from upstream metadata timestamps. This internal check detects stale or mismatched adapter observations; it cannot prove a future adapter actually contacted GitHub. Its contract and integration tests must enforce that separately. No remote TTL, transferable proof or Pipeline `validUntil` is invented here. The unavoidable check/use race remains.

This increment introduces no credentials, callback/webhook implementation, persistence, capture, sanitization pipeline or publication. Canonical event context remains `pipeline`, `execution`, `commit`, `service`, `environment`, `correlationId`; none of its serialization, identity, retry or settlement semantics changes. Before the first capture/publication increment, trust validation, sanitization/minimization and context preservation must be checked together under PD-033. Tests for capabilities not yet implemented are not claimed.

The [connector contract proposal](deployment-contract.md#connector-contract-proposal-for-decision) records dependent decisions in the existing owning document. Log/Event and Recommendation's human authorization contracts remain gates to integrated safe exposure; their background processing is not halted by an individual's disconnect.


Verification on 2026-11-06: the coordinator reran the README's wrapper `clean verify` command successfully (64 tests, no failures/errors/skips), compiled all domain/application sources against an empty dependency classpath, checked `jdeps` (`java.base` only) and inspected the JAR for absence of test doubles. That review found no remaining findings in its scope after fixes for provider identity, connection generation, typed failures and per-target denial. A later user-triggered architecture review found duplicated operation-to-administration policy and incomplete Spring composition; the correction is recorded below, so that earlier review is not a complete architecture certification. These are isolated local checks, not real provider/authentication/storage/broker integration. No source, publication or deployment approval is inferred from the results.


The proposed connector contract now treats discovery, registration and recipient eligibility as one reviewable semantic revision. Two consumer gaps require explicit agreement: Pipeline supplies repository IDs without installations whereas the core requires a verified repository/installation target; Pipeline also requires `validUntil` whereas the core exposes only invocation-scoped observation time. The proposal recommends server-side resolution of a unique current admitted installation and request-deadline-bound validity, respectively. Neither is implemented or implied by the existing ports. OBO and Key Vault Secrets usage remain recommendations; the approved Key Vault product choice is preserved. Physical custody, delegated identity configuration and consumer compatibility must be accepted before their dependent adapters can be implemented. The existing local checks are not evidence of an accepted remote contract.


## Responsibility and framework alignment

Architecture review after the user's conditional approval found a real local implementation drift: Ingestion's `requiresAdministration` mapping and `allowed` result duplicated Pipeline's operation/capability decision. The service boundaries in ADR-020 remain valid; the correction removes that mapping and result instead of introducing another policy service or shared policy library. The user's conditional approval is not recorded as unconditional approval of OBO, Secrets mode or the remote contract.

| Responsibility | Owner and boundary |
|---|---|
| GitHub linkage, credential custody, admission and current provider facts | Ingestion. Verify identity, user AND App access, SSO, account/installation and provenance; protect its own discovery/metadata surfaces. |
| Repository/Pipeline registration, grants, effective AIOps capabilities and operational transactions | Pipeline. Combine verified external facts with owned grants and apply VIEW/OPERATE/CONFIGURE/registration rules. No direct GitHub call or credential custody. |
| Remote fact integrity | Each side validates its own boundary. Pipeline checks the binding/freshness of received facts; that does not duplicate provider verification. Ingestion authenticates and authorizes use of its own technical surface; that does not grant rights over Pipeline resources. |
| Recipient query protection | The caller/subject/resource trust contract remains pending. Do not infer a second grant policy or extra provider call in Ingestion; any additional disclosure guard needs its own justified contract. |

The [Java/Spring conventions](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/development/java-spring-conventions.md) require framework-free domain/application **and** external composition. The initial JDK-only core satisfied the first requirement but left the Spring Boot composition incomplete; missing HTTP and custody contracts did not require deferring that composition. The correction adds a non-web Boot entry point and external bean construction. Without real required ports, startup must fail explicitly rather than install successful doubles or claim an operational connector. No HTTP listener or API contract is introduced by this bootstrap.

The same conventions select JPA/Hibernate and Flyway for owned relational tables. ADR-020 approves logical credential/linkage ownership, but not the physical schema, tables, permissions or custody protocol. Key Vault selection does not supply that missing model. Do not add JPA/Flyway, entity bases, migrations or copied Pipeline `ops` mappings until the applicable physical model is accepted. Existing constructor injection, functional packages, query handlers/ports, UTC observations and meaningful isolated checks remain in use; absent entities/commands/REST resources are not empty scaffolding requirements.


Correction verification on 2026-11-06: the coordinator reran the README wrapper command: 68 tests passed (64 core and 4 Spring composition), with no failures, errors or skips. The 18 domain/application sources compiled with an empty dependency classpath and `jdeps` reported `java.base` only. Tests preserve both true and false provider administration facts, including readable nonadministrator workflow metadata under REGISTER, and verify missing-port failures by exact dependency type. The packaged Boot application was executed locally and exited 1 for missing `CurrentGitHubLink` in a non-web context, as intended; its JAR excludes test doubles, test libraries, web server and ORM/migration dependencies. Independent review found no remaining material findings in this correction. No Pipeline tests or remote integrations were run, and no neighboring repositories were modified.


Domain values live in `github/domain/model/valueobjects`: `AccessContext`, `AccessOperation`, `RepositoryTarget`, `RepositoryFacts`, `WorkflowMetadata` and `GitHubLink`. Their equality/validation concerns a value or an immutable observed snapshot. In particular, `GitHubLink` describes the observed identity/revision; it does not implement the link lifecycle as an entity or aggregate root. Application response projections remain in `application/queries/GitHubViews`. The initial flat `domain/model` placement omitted this part of the convention and has been corrected with its callers/tests.

There are currently no implemented entities with an owned mutation lifecycle or aggregate consistency boundary, so `entities` and `aggregates` packages are not created empty. This follows domain semantics, not the absence of JPA: a future link-lifecycle model can define entities/aggregates independently of persistence once its behavior, invariants and atomicity are specified. No base entity, aggregate or persistence mechanism is added by this package correction.

The package correction passed the existing wrapper `clean verify` checks (68 tests), an empty-classpath compilation of the 18 domain/application sources and the stale-package-reference/whitespace checks. No behavior or new lifecycle model was introduced.


The user has now authorized coordinated local documentation work in pipesor-docs and Ingestion. The two parts share identifier `github-connector-draft-0.2`: [Docs domain decisions](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md) owns the proposed physical metadata/constraints following DD-019; the [Ingestion contract proposal](deployment-contract.md#connector-contract-proposal-for-decision) owns exchange/identity semantics and Pipeline mapping. The proposed records are not in canonical `domain.yml`, generated diagrams or migrations. Cross-repository links follow main even while the new draft is local and unpublished. Key Vault product selection is retained; neither these edits nor conditional approval accept the schema, OBO/Secrets mode, admission administration or browser callback protocol. Other service repositories remain unchanged.


## Accepted connector design 0.2

On 2026-11-07 the user explicitly approved the coordinated design: “Propuesta aprobada”. This supersedes the conditional/proposed status of its documented physical model, OBO, request-confined validity and Key Vault Secrets/PKCE usage; earlier proposal descriptions above are historical. It authorizes the corresponding local canonical-model, migration, domain/application and persistence implementation with isolated PostgreSQL verification. It does not authorize publication, Azure actions or invent the browser/return protocol, admission administration, actual audiences/scopes, deadlines, revocation scope or restore operating mechanism that the design expressly left open. Those gaps block only their dependent adapters/activation. DD-020 in Docs records this local acceptance; neither its source branch nor these local changes are integrated main.

The approved metadata model is now incorporated locally in Docs `domain.yml` 1.2.0 and its generated derivatives. Ingestion implements `GitHubConnection` as the aggregate root, lifecycle entities and value objects, command/query handlers, scoped JPA persistence and V1 for four owned `ingestion` tables. The earlier statements about absent entities, ORM dependencies and missing-link startup describe previous increments. Domain/application remain framework-independent; Pipeline still owns registration, grants and capability policy. No downstream table or repository was changed.

Final local verification on 2026-11-07: the README wrapper command passed 96 tests, including 25 metadata checks on real PostgreSQL 17.11, with no failures/errors/skips. Canonical Docs generation, 106 unit tests and 9 SQL checks passed on PostgreSQL 16.15 with pgvector 0.8.7. Independent review closed its findings within this scope. These checks do not certify HTTP, GitHub, Entra, Key Vault, shared functional audit or restore/recovery integration. In particular, an uncertain nonterminal refresh keeps final cleanup blocked even after late acknowledgement; terminal reconciliation remains a gate for a real executor.

## Cross-service proposal analysis

On 2026-11-08, read-only analysis of Pipeline, Log/Event and NLP found an implemented, locally accepted execution snapshot contract in Pipeline, an implemented but unapproved evidence candidate in NLP, and no Log/Event application. The [contract proposals](deployment-contract.md#cross-service-contract-proposals--2026-11-08) preserve Pipeline's contract and propose a separate routed evidence revision, shared logical references, durable capture/publication semantics and connector/browser review choices. Correlation/Recommendation and frontend were inspected for the connected handoff/security gates; no consumer repository was modified. These are proposals rather than changes to canonical ownership or accepted event semantics. Full provider/broker integration and consumer compatibility tests remain pending acceptance and implementation.

The user subsequently approved these contracts explicitly on 2026-11-08. The deployment contract records that local acceptance and distinguishes missing execution inputs from decisions already accepted. The recommendations no longer require repeated approval; a conditional browser option, unspecified physical capture/publication model and absent runtime values are not made executable merely by accepting the design. Docs PR #9 is now integrated at `e22627cfee10b0b02edab084b98eb18926c678b5`; earlier statements that DD-020 remains unintegrated are historical. This local acceptance does not update consumer implementations or authorize writes to their repositories.

## Local normalized-message implementation

The normalization feature now validates whitelisted internal observations, compares the expected machine-scope binding and requires the external protection policy before constructing a serializable result. It computes logical owner references without persisting downstream objects. There is no aggregate/entity lifecycle in this feature yet: immutable observations and output plans are values; the proposed durable lifecycle must be modeled before persistence. Domain/application remain framework-free; Jackson is confined to the outgoing-message transformer and Spring composition remains external. No production protection adapter or successful fallback is registered.

The resulting complete in-memory plan emits the existing Pipeline snapshot and, for completed failure/timed_out attempts, protected evidence v2. A failed attempt without classified evidence is rejected. Generated messages preserve caller-reserved UUIDs, source timestamps, correlation, explicit null context and 64-bit evidence sequence; arrays/maps and payload bytes are immutable. This neither authenticates GitHub nor makes a capture durable. Reusing a UUID with different input or running a changed policy is not prevented until the durable ledger exists, so real retries must eventually reuse persisted frozen messages.

Verification on 2026-11-08 passed 122 tests, including 26 normalization checks and 25 PostgreSQL metadata checks, with no failures/errors/skips. Forty core sources compiled without dependencies and `jdeps` reported only `java.base`. Pipeline's actual transformer compiled in isolation accepted two generated snapshots with matching owner reference rules; its database/event settlement was not exercised by that check. NLP v1 specifically rejected the generated v2 evidence. The JAR still fails explicitly for missing DataSource configuration; no operational GitHub/Entra/Key Vault/broker path was validated. The exact evidence is also recorded in README.

The [durable lifecycle definition](https://github.com/pipesor-org/pipesor-docs/blob/main/model/domain_decisions.md#capture-publication-draft-0.1) was prepared on Docs branch `feature/capture-publication-contract` and subsequently adopted locally through the user's “Vamos a por ello” direction. DD-021/domain 1.3.0 now define the receipt, delivery alias, observation and publication-output records and their canonical constraints; V2 derives from that model. The source and derivatives are now integrated in Docs main at `65792190d2ee32f0721d22848cd62cee1fd0d630`. These technical records preserve Ingestion ownership and do not replace Log/Event evidence or Pipeline objects. Runtime scope supplier, actual extraction, operational budgets and deletion/recovery inputs remain pending.

## Local durable capture/publication implementation

The capture root and phase operations reserve IDs during atomic receipt/alias acceptance, validate claimed protected preparation and persist a closed plan before any send. Observation identity uses versioned length-prefixed canonical protected fields and original source timestamps, excluding generated IDs, correlation, deployment policy versions and technical clocks. Equivalent observations preserve the original plan; source timestamps retain nanosecond precision. Native PostgreSQL locking/constraints and JPA mappings implement whole-phase transactions independently of domain/application. The pure encoder is an infrastructure dependency, not a caller-provided callback that can substitute arbitrary payload bytes.

Current-machine-scope and broker ports remain mandatory and outside SQL transactions. Publication confirms each output separately; transient/uncertain attempts reuse frozen bytes/IDs, and stale claimants cannot update successors. Consistent observation-before-output/receipt locking covers confirmation, blocking, retry and payload removal. SQL guards reject cross-context references, changed frozen plans, association without preparation and premature purge. Only confirmed bodies past the supplied horizon can be removed; pending/blocked work and dedup metadata remain, with no invented 365-day technical retention or purge procedure. At-least-once does not promise global order or exactly-once effects.

On 2026-11-09 the coordinator's README `clean verify` passed 149 tests, including 49 real PostgreSQL 17.11 persistence checks (24 capture, 25 connector) and 3 fingerprint checks. The final build includes strengthened SQL-failure assertions, elapsed retry-deadline coverage and late scope-denial/claim-takeover coverage; independent review closed its findings. Actual Docs `make all test test-postgres` passed generation, 109 unit checks and 14 PostgreSQL 16.15/pgvector 0.8.7 checks. Its eight existing descriptive C4 warnings remain; no fallback icons were used, and the rendered processing view was visually inspected. V2 matches the actual canonical SQL/guards byte-for-byte. Provider/protection/scope/broker behavior is synthetic in these checks; no live GitHub/Entra/Key Vault/Service Bus path or public webhook is enabled. Review found no remaining findings in this implemented scope.


## Current local web and production-boundary adapters

The current increment supersedes the historical non-web bootstrap described above. The servlet composition exposes the approved versioned discovery/access and metadata status/disconnection surface, with signed delegated identity/caller validation and explicit request limits. Domain/application remain independent of Spring, HTTP, ORM and Azure. Pipeline continues to own Repository, Pipeline, grants and capability decisions; the new adapter only returns GitHub facts. Missing trusted link/provider/admission/selection adapters yield unavailable, including when an ACTIVE metadata row exists.

An opt-in bounded known-pattern protector now implements the mandatory protection port; no pass-through exists. Unsafe/unsupported evidence is refused before message construction. An opt-in synchronous managed-identity Service Bus adapter sends frozen outputs outside SQL transactions and preserves duplicate/retry semantics. Capture/protection/messaging remain disabled by default and no scheduler or webhook is active. See [configuration and limitations](deployment-contract.md#local-adapter-configuration).

Docs DD-020/DD-021 are integrated in main at consulted revision `65792190d2ee32f0721d22848cd62cee1fd0d630`; historical overlay descriptions above do not describe their current status. No neighboring repository is modified by this increment. NLP still needs the v2 consumer adaptation, Log/Event and Correlation their receivers/idempotency, and Pipeline its remote GitHub access adapter. Log/Event and Recommendation human APIs must enforce the approved evidence/diagnostic access checks; their implementation is not established by protecting or publishing evidence here.

The agent pilot reused the existing bounded analysis and accepted decisions. Independent review found and drove concrete fixes for compound-secret refusal, optional-feature startup and discovery binding. Missing origins/admission configuration were asked once and retained as activation gates; no new approval was inferred for external writes. This is evidence about this service's pilot, not a mandate for other services.

Final local adapter verification: README `clean verify` passed 236 tests with no failures/errors/skips, including the production servlet composition on real PostgreSQL, real HTTP with locally signed JWTs and bounded policy/publisher failure cases. Fifty-two core sources compiled without dependencies and use only `java.base`. Independent review closed the material findings. External identity/provider/broker/consumer connectivity and hosted CI were not exercised.


## Private credential boundaries

Private OAuth token transport and Key Vault pair/PKCE custody now implement the locally accepted protocol independently of the servlet API. Ports remain in application and Azure/HTTP/JSON code stays in infrastructure. Exact-version owner/vault validation and complete pair storage do not substitute for current user authorization, restoration safety or the durable lifecycle executor. No new table, cross-service write or runtime endpoint is introduced. See [private credential adapter constraints](deployment-contract.md#local-private-credential-adapters); browser/admission/recovery gates continue to block their dependent execution.

## Private refresh execution

The one-shot refresh executor coordinates the integrated metadata, custody and OAuth boundaries. It checks connection identity before mutation, takes the exact active credential version from a short SQL snapshot and requires durable claim/send markers before remote refresh. Conditional activation retains the existing generation/revision guards; no network call belongs inside a metadata transaction. Late exact custody acknowledgements are retained without overriding a disconnect or expired operation.

The executor is private and uncomposed. Browser completion, current GitHub authorization, operational restore safety and terminal cleanup/reconciliation are still separate gates. In particular, a nonterminal refresh excludes use of the previous pair even while its generation row is ACTIVE. This adds neither a Pipeline capability decision nor an HTTP refresh endpoint.

## GitHub read-provider boundary

The provider implementation consumes GitHub's user/App intersection and actual user identity through read-only REST operations. Installation suspension, App/account identity, user permissions, SSO-required/partial results and complete pagination are checked at that boundary. Workflow metadata is linked to the repository proven by those reads; public repository metadata does not establish authorization. The provider supplies facts without registering Pipeline objects or deciding AIOps capabilities.

Private credential supply remains a separate operational gate: it must bind the exact generation, reject nonterminal refresh and honor restore/admission rules before this provider can be composed. No operational credential reader or new HTTP contract is added by the provider implementation.


## Publication execution

An application worker now connects bounded ready-receipt discovery to the existing publication phase operation. Its advisory cursor avoids starvation by repeatedly failing receipts without granting a claim or changing durable identity. The external SQL selector and opt-in managed runner preserve framework-free application code; the ledger still owns claims, leases, retries and output confirmation. No schema or message contract changes are introduced. See [runtime requirements and limitations](deployment-contract.md#durable-publication-worker); production source scope, provider identity and operational capacity remain distinct activation inputs.


## Machine-source authentication and durable webhook entry

The machine transport now verifies the actual App, installation/account, narrowed read-only token and selected workflow/run attempt. Jobs/steps/logs remain private raw source data; exact original timestamps and correlation are retained, and changed source versions, unsupported log encodings or unavailable authority fail closed. No user token is substituted for machine capture. Signed log downloads use a separate credential-free request to approved hosts.

The operator-owned configuration and Key Vault Secrets usage for App signing/webhook material were explicitly approved locally on 2026-11-10. File snapshots supply current machine scope and tenant installation admission; they do not register Pipeline objects/grants or certify restored personal connections. Private RS256 signing and exact-version custody add no key generation, installation, resource or permission capability.

The approved opt-in webhook endpoint verifies HMAC before parsing, resolves scope independently and commits a minimized receipt before returning selected-delivery success. It neither persists raw bodies nor treats a signature as authority to read repositories. Timeouts may leave a committed receipt with an unknown acknowledgement; durable body identity and delivery-conflict rules remain authoritative. Bounded concurrent work and a managed timer protect the public edge without changing the human Entra boundary or making OpenAPI public.

The pilot is this repository's Verify workflow, completed actions only; live resources do not yet exist. Stage/excerpt attribution and preparation orchestration remain gated by reviewed source mapping, while browser continuity and restore/cleanup retain their separate decisions. CI was deferred by the user and was not worked on in this increment.


## Approved Verify preparation boundary

The [locally approved profile](deployment-contract.md#verify-preparation-profile-v1--approved-locally) maps only the named pilot's proven step archive and explicit Maven phases. `VerifyPreparationProfile` returns private whitelisted observations; `PrepareGitHubCapture` coordinates the durable claim, source binding, mandatory protection, reserved IDs, encoded body limits and atomic ledger preparation. Domain/application remain independent of Spring, ZIP/HTTP, JPA and Azure; archive transport and opt-in composition stay in infrastructure/configuration. No Pipeline object, grant, execution transition or downstream schema is created or changed.

The operation is explicit per receipt; automatic preparation dispatch/recovery is not implemented. Flags remain disabled, environment resources are absent, and local provider fixtures do not certify a live GitHub → preparation → broker flow. Log/Event, NLP, Correlation and Recommendation still need their receiving/authorization contracts enforced before claiming a safe integrated flow; human evidence and diagnosis access must not bypass repository restrictions.


## Explicit preparation dispatch boundary

The [bounded preparation dispatcher](deployment-contract.md#explicit-preparation-dispatch--local-implementation) selects accepted, unleased receipts through a read-only CQRS port and invokes the existing preparation operation outside SQL transactions. The ledger owns claims/fences and the complete frozen plan. Caller-supplied absolute claim/recovery horizons retain the existing contract; advisory cursor/budget/stop state is not authority or durable retry state. No new schema, retention policy, external API or scheduler is added. Continuous operation and the new CI source profile remain activation gates.


## Local continuous preparation scheduling

The [approved preparation scheduler](deployment-contract.md#continuous-preparation-scheduling--approved-locally) is an opt-in infrastructure lifecycle around the existing bounded worker. The application/domain remain framework-free. Each tick derives fixed claim/recovery horizons from one UTC sample; monotonic cadence and a generation stop signal prevent rapid/overlapping starts, including stop/start with old I/O still in flight. Database claims/fences remain authoritative across instances. The scheduler does not add an event input, GitHub workflow trigger, schema, evidence-retention policy, Pipeline behavior or AI processing. Real activation still requires a supported source profile and environment readiness.
