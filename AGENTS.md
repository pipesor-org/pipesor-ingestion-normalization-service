# Repository instructions

## Context by task

- Use [README.md](README.md#development-and-verification) for setup, runnable commands and contribution policies. Add verified commands there when introducing application tooling; reference them rather than maintaining a second command list here.
- For capture, normalization, privacy or publication changes, consult [architecture](docs/architecture.md#service-relationships) and its current source model, including this service's canonical event context. For GitHub authentication changes, consult the applicable ADR-005 source linked there. Follow the references for the affected subject, not the entire documentation set for every edit.
- For dependency selection, runtime configuration, credentials or delivery work, consult the relevant sections of the [deployment contract](docs/deployment-contract.md). Its development baseline records the authorized version-selection policy; the data lifecycle records the absence of owned persistent tables.
- Treat local architecture summaries as navigation, not frozen specifications. Use the accessible `../../pipesor-docs/` checkout or linked sources on `main`, accounting for later accepted decisions and relevant uncommitted changes. Inspect uncommitted source changes separately; their presence does not establish integration or approval. Report unavailable or conflicting evidence when it affects the task. Documentation links must follow `main` across repositories and relative paths within this repository; a consulted revision may be recorded as text only.

## Implementation constraints

- Keep GitHub capture within the authorized repository/workflow scope and the read-only GitHub App installation-token boundary. Do not turn evidence collection into workflow mutation or artifact publication. Validate incoming source data and caller authenticity at the trust boundary using the agreed integration contract; do not assume an inbound event authorizes subsequent reads.
- Apply masking, anonymization, secret filtering and data minimization before evidence reaches the broker or downstream AI processing. Check failure paths and logs for sensitive-data exposure as well as the published payload. Preserve provenance and canonical context, including `correlationId`, through normalization.
- Do not introduce persistent tables, downstream-service writes or a separate privacy gateway to complete ingestion. Check capture, normalization and publication together when changing behavior; use the agreed message and failure/re-delivery contracts rather than assuming exactly-once publication or choosing a deduplication scheme by inference.
- A request to implement a local increment authorizes its required local code, tooling and checks. Do not create cloud resources, permissions or secrets, or modify neighboring repositories as a side effect. Keep credentials and sensitive evidence out of committed files and reported output.
- If source authorization, webhook verification, event semantics or another required contract is unresolved, identify the specific pending decision and its impact. Continue independent work; do not invent integration behavior or block the whole service because an unrelated decision remains open.

## Verification and handoff

Use the applicable checks documented in the README. Add behavior checks with the first executable increment, including material failure cases for trust validation, sanitization, context preservation and publication. Use synthetic evidence and mocked integration boundaries for isolated tests; live GitHub or Azure checks need their own agreed scope and access.

For documentation-only edits, check affected links and source coherence; parse TOML when changing agent configuration. Do not claim a build or integration passed before it exists and has run. Keep the documentation that owns a changed behavior or command current in the same change. Report what changed, checks actually performed and remaining blockers. For substantial work spanning sessions, keep a task-specific plan with scope, acceptance criteria and verification progress; a small edit needs no plan file.

When a focused review is warranted and delegation is authorized, `ingestion_reviewer` checks trust boundaries, privacy, canonical context and publication. Supply the task and affected paths. Its review supplements the main agent's verification and human review.
