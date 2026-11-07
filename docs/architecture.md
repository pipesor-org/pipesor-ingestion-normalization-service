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

Source consulted: pipesor-docs main at 18dacfbfcee22a92bb98dba85a378b023e96ac70 plus its approved, uncommitted [ADR-020](https://github.com/pipesor-org/pipesor-docs/blob/main/docs/architecture/decisions/ADR-020-github-user-access-and-workflow-onboarding.md) overlay. That local source is not integrated main; the earlier consumption record above remains historical. The user authorized recording this impact and the agent workflow, not implementing or deploying the connector in this change.

- Entra authenticates AIOps users. Ingestion owns GitHub linkage, user access-token lifecycle, discovery and verified repository/workflow/access facts. Installation tokens remain for machine capture; they do not substitute for lost user authorization. Keep GitHub interactions read-only.
- Verify effective user AND App access, tenant-admitted account/installation and required SSO; repository administration applies where the approved operation requires it. Callback authorization must recover the Entra-bound attempt, use state/PKCE and prevent replay; browser identity/permission flags are not proof.
- Pipeline owns Repository/Pipeline registration, operations and AIOps grants. It consumes verified facts without GitHub tokens. Ingestion must not write ops or decide Pipeline capabilities. Dashboard connection/discovery and internal Pipeline verification are distinct authenticated surfaces.
- The internal contract must bind actor/subject, tenant, operation, repository and installation, with current validity and explicit denied/disconnected/unavailable behavior. Routes, schemas, audiences/scopes, verifiable subject propagation, timeouts and versioning remain PD-034/035. No wire contract is selected here, and Pipeline's application port is not an approved HTTP payload.
- Logical linkage/credential ownership does not approve a database schema, secret store, encryption/key management, migration or credential retention/deletion mechanism. Those contracts remain pending before persistence; do not reuse ops/evidence/audit to store tokens or create users/roles/teams.
- Preserve sanitization before publication, canonical context/provenance and agreed event semantics. No automatic backfill, workflow mutation or inferred change to in-flight event acceptance. User unlink does not by itself stop shared machine monitoring while the installation remains authorized.
- Log/Event and Recommendation must prevent human evidence/diagnosis/review APIs from bypassing repository restrictions under an agreed authorization context. These are external-owner dependencies; this repository change does not modify those services or approve cloud resources.

The [task-based agent workflow](../AGENTS.md#task-based-agent-workflow) uses a bounded analyst brief, implementation worker and independent ingestion reviewer. The first analysis should reconcile historical guidance against this approved evolution, retain decision provenance and isolate genuinely blocked work. No application, connector or live GitHub/Entra/Service Bus integration is implemented or certified by agent configuration.
