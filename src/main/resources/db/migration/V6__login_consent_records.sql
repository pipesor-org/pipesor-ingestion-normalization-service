-- Consent given at sign-in (ADR-029; Ley 29733, D.S. 016-2024-JUS arts. 4 and 9).
-- The login attempt carries the accepted version of the Privacy Policy and Terms of Use; its
-- creation time is the acceptance time. Attempts created before this migration have no version.
ALTER TABLE ingestion.github_login_attempts
  ADD COLUMN consent_version date,
  ADD CONSTRAINT github_login_attempts_consent_version_ck
    CHECK (consent_version IS NULL OR consent_version <= (created_at AT TIME ZONE 'UTC')::date + 1);

-- A CHECK cannot tell historical rows from new ones, and NOT VALID would also block the claim/fail
-- transitions of attempts in flight during the deployment, so new rows are checked on insert.
CREATE FUNCTION ingestion.require_login_consent_version() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$ BEGIN
    IF NEW.consent_version IS NULL THEN
        RAISE EXCEPTION 'Login attempts require the accepted consent version'
          USING ERRCODE = '23514', CONSTRAINT = 'github_login_attempts_consent_required_ck';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER github_login_attempts_consent_required BEFORE INSERT ON ingestion.github_login_attempts
FOR EACH ROW EXECUTE FUNCTION ingestion.require_login_consent_version();

-- One append-only row per successful login, written in the transaction that offers the handoff.
-- entra_object_id is the platform principal object id, as in github_connections (PlatformIds.human).
CREATE TABLE ingestion.consent_records (
  id uuid PRIMARY KEY NOT NULL,
  tenant_id uuid NOT NULL,
  entra_object_id uuid NOT NULL,
  github_user_id text NOT NULL,
  documents_version date NOT NULL,
  accepted_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL,
  source text NOT NULL,
  login_attempt_id uuid NOT NULL,
  correlation_id text NOT NULL,
  revoked_at timestamptz,
  revocation_reference text,
  CONSTRAINT consent_records_login_attempt_uq UNIQUE (login_attempt_id),
  CONSTRAINT consent_records_login_attempt_fk FOREIGN KEY (login_attempt_id)
    REFERENCES ingestion.github_login_attempts (id),
  CONSTRAINT consent_records_github_user_ck CHECK (github_user_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT consent_records_source_ck CHECK (source IN ('LOGIN_CHECKBOX')),
  CONSTRAINT consent_records_correlation_ck CHECK (btrim(correlation_id) <> ''),
  CONSTRAINT consent_records_timestamps_ck CHECK (accepted_at <= recorded_at),
  CONSTRAINT consent_records_version_ck CHECK (documents_version <= (accepted_at AT TIME ZONE 'UTC')::date + 1),
  CONSTRAINT consent_records_revocation_ck CHECK ((revoked_at IS NULL) = (revocation_reference IS NULL)
    AND (revocation_reference IS NULL OR btrim(revocation_reference) <> ''))
);

CREATE INDEX consent_records_principal_ix
  ON ingestion.consent_records (tenant_id, entra_object_id, recorded_at DESC);

-- Append-only: no DELETE or TRUNCATE, and the only UPDATE is the single manual revocation that sets
-- revoked_at and revocation_reference from NULL while every other column stays unchanged.
CREATE FUNCTION ingestion.guard_consent_record() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.revoked_at IS NULL AND OLD.revocation_reference IS NULL
       AND NEW.revoked_at IS NOT NULL AND NEW.revocation_reference IS NOT NULL
       AND ROW(NEW.id, NEW.tenant_id, NEW.entra_object_id, NEW.github_user_id, NEW.documents_version, NEW.accepted_at,
               NEW.recorded_at, NEW.source, NEW.login_attempt_id, NEW.correlation_id)
           IS NOT DISTINCT FROM ROW(OLD.id, OLD.tenant_id, OLD.entra_object_id, OLD.github_user_id, OLD.documents_version,
               OLD.accepted_at, OLD.recorded_at, OLD.source, OLD.login_attempt_id, OLD.correlation_id) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Consent records are append-only; only one revocation may be recorded'
      USING ERRCODE = '23514', CONSTRAINT = 'consent_records_append_only_ck';
END;
$$;
CREATE TRIGGER consent_records_guard BEFORE UPDATE OR DELETE ON ingestion.consent_records
FOR EACH ROW EXECUTE FUNCTION ingestion.guard_consent_record();
CREATE TRIGGER consent_records_no_truncate BEFORE TRUNCATE ON ingestion.consent_records
FOR EACH STATEMENT EXECUTE FUNCTION ingestion.guard_consent_record();
