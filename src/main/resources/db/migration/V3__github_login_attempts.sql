-- One-time browser login attempts and handoffs (platform identity contract v1, section 5).
-- Raw OAuth state and handoff values are never stored: only their SHA-256 digests.
CREATE TABLE ingestion.github_login_attempts (
  id uuid PRIMARY KEY NOT NULL,
  state_digest bytea NOT NULL,
  pkce_secret_object_ref text NOT NULL,
  pkce_secret_version_ref text NOT NULL,
  return_path text NOT NULL,
  phase text NOT NULL,
  expires_at timestamptz NOT NULL,
  correlation_id text NOT NULL,
  connection_id uuid,
  generation_id uuid,
  github_user_id text,
  github_login text,
  handoff_digest bytea,
  handoff_expires_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT github_login_attempts_phase_ck CHECK (phase IN ('READY','CLAIMED','HANDOFF_READY','COMPLETED','FAILED')),
  CONSTRAINT github_login_attempts_pkce_owner_ck CHECK (pkce_secret_object_ref = 'pkce:' || id::text),
  CONSTRAINT github_login_attempts_version_ck CHECK (btrim(pkce_secret_version_ref) <> '' AND lower(btrim(pkce_secret_version_ref)) <> 'latest'),
  CONSTRAINT github_login_attempts_return_ck CHECK (return_path ~ '^/'),
  CONSTRAINT github_login_attempts_correlation_ck CHECK (btrim(correlation_id) <> ''),
  CONSTRAINT github_login_attempts_expiry_ck CHECK (expires_at > created_at),
  CONSTRAINT github_login_attempts_timestamps_ck CHECK (updated_at >= created_at),
  CONSTRAINT github_login_attempts_handoff_ck CHECK (
    (phase IN ('HANDOFF_READY','COMPLETED')) = (handoff_digest IS NOT NULL)
    AND (handoff_digest IS NULL OR (handoff_expires_at IS NOT NULL AND connection_id IS NOT NULL
         AND generation_id IS NOT NULL AND github_user_id ~ '^[1-9][0-9]*$' AND btrim(github_login) <> '')))
);

CREATE UNIQUE INDEX github_login_attempts_state_uq ON ingestion.github_login_attempts (state_digest);
CREATE UNIQUE INDEX github_login_attempts_handoff_uq ON ingestion.github_login_attempts (handoff_digest) WHERE handoff_digest IS NOT NULL;
