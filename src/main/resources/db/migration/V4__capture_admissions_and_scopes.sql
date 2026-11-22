-- ADR-023: operator repository admissions and Pipeline-driven workflow capture scopes.
CREATE TABLE ingestion.repository_admissions (
  tenant_id uuid NOT NULL,
  app_id text NOT NULL,
  account_id text NOT NULL,
  installation_id text NOT NULL,
  repository_id text NOT NULL,
  admitted_at timestamptz NOT NULL,
  admitted_by text NOT NULL,
  PRIMARY KEY (tenant_id, repository_id),
  CONSTRAINT repository_admissions_ids_ck CHECK (app_id ~ '^[1-9][0-9]*$' AND account_id ~ '^[1-9][0-9]*$'
    AND installation_id ~ '^[1-9][0-9]*$' AND repository_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT repository_admissions_by_ck CHECK (btrim(admitted_by) <> '')
);
CREATE INDEX repository_admissions_installation_ix ON ingestion.repository_admissions (tenant_id, installation_id);

CREATE TABLE ingestion.capture_workflow_scopes (
  capture_domain text NOT NULL,
  tenant_id uuid NOT NULL,
  app_id text NOT NULL,
  account_id text NOT NULL,
  installation_id text NOT NULL,
  repository_id text NOT NULL,
  workflow_id text NOT NULL,
  pipeline_id uuid,
  status text NOT NULL,
  allowed_actions text[] NOT NULL,
  replay_window interval NOT NULL,
  revision bigint NOT NULL,
  scope_revision text NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (capture_domain, tenant_id, app_id, repository_id, workflow_id),
  CONSTRAINT capture_workflow_scopes_status_ck CHECK (status IN ('ACTIVE','PAUSED')),
  CONSTRAINT capture_workflow_scopes_ids_ck CHECK (app_id ~ '^[1-9][0-9]*$' AND account_id ~ '^[1-9][0-9]*$'
    AND installation_id ~ '^[1-9][0-9]*$' AND repository_id ~ '^[1-9][0-9]*$' AND workflow_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT capture_workflow_scopes_actions_ck CHECK (cardinality(allowed_actions) > 0
    AND allowed_actions <@ ARRAY['requested','in_progress','completed']::text[]),
  CONSTRAINT capture_workflow_scopes_replay_ck CHECK (replay_window > interval '0'),
  CONSTRAINT capture_workflow_scopes_revision_ck CHECK (revision > 0 AND scope_revision ~ '^[A-Za-z0-9._:-]{1,128}$'),
  CONSTRAINT capture_workflow_scopes_admitted_fk FOREIGN KEY (tenant_id, repository_id)
    REFERENCES ingestion.repository_admissions (tenant_id, repository_id)
);
