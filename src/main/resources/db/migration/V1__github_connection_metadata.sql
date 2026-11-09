-- Derived from approved local domain.yml 1.2.0 / DD-020 (2026-11-07).
CREATE SCHEMA IF NOT EXISTS ingestion;

CREATE TABLE "ingestion"."github_connections" (
  "id" uuid PRIMARY KEY NOT NULL,
  "tenant_id" uuid NOT NULL,
  "entra_object_id" uuid NOT NULL,
  "revision" bigint NOT NULL,
  "active_generation_id" uuid,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "github_connections_revision_ck" CHECK (revision >= 0),
  CONSTRAINT "github_connections_timestamps_ck" CHECK (updated_at >= created_at)
);

CREATE TABLE "ingestion"."github_connection_generations" (
  "id" uuid PRIMARY KEY NOT NULL,
  "connection_id" uuid NOT NULL,
  "github_user_id" text,
  "state" text NOT NULL,
  "secret_object_ref" text NOT NULL,
  "active_secret_version_ref" text,
  "access_expires_at" timestamptz,
  "refresh_expires_at" timestamptz,
  "activated_at" timestamptz,
  "revision" bigint NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "github_connection_generations_state_ck" CHECK (state IN ('PREPARING','ACTIVE','BLOCKED','INVALIDATED')),
  CONSTRAINT "github_connection_generations_revision_ck" CHECK (revision >= 0),
  CONSTRAINT "github_connection_generations_activation_ck" CHECK ((activated_at IS NULL AND state IN ('PREPARING','INVALIDATED')) OR (activated_at IS NOT NULL AND github_user_id IS NOT NULL AND active_secret_version_ref IS NOT NULL AND access_expires_at IS NOT NULL AND refresh_expires_at IS NOT NULL)),
  CONSTRAINT "github_connection_generations_object_ref_ck" CHECK (btrim(secret_object_ref) <> ''),
  CONSTRAINT "github_connection_generations_user_identity_ck" CHECK (github_user_id IS NULL OR btrim(github_user_id) <> ''),
  CONSTRAINT "github_connection_generations_version_ref_ck" CHECK (active_secret_version_ref IS NULL OR (btrim(active_secret_version_ref) <> '' AND lower(btrim(active_secret_version_ref)) <> 'latest')),
  CONSTRAINT "github_connection_generations_timestamps_ck" CHECK (updated_at >= created_at),
  CONSTRAINT "github_generations_object_owner_ck" CHECK (secret_object_ref = 'generation:' || id::text)
);

CREATE TABLE "ingestion"."github_authorization_attempts" (
  "id" uuid PRIMARY KEY NOT NULL,
  "connection_id" uuid NOT NULL,
  "tenant_id" uuid NOT NULL,
  "entra_object_id" uuid NOT NULL,
  "expected_connection_revision" bigint NOT NULL,
  "expected_generation_id" uuid,
  "target_generation_id" uuid NOT NULL,
  "state_digest" bytea NOT NULL,
  "expires_at" timestamptz NOT NULL,
  "phase" text NOT NULL,
  "consumed_at" timestamptz,
  "pkce_secret_object_ref" text NOT NULL,
  "pkce_secret_version_ref" text,
  "pkce_cleanup_state" text NOT NULL,
  "pkce_cleanup_result" text,
  "approved_return_ref" text NOT NULL,
  "correlation_id" text NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "github_authorization_attempts_phase_ck" CHECK (phase IN ('PREPARING','READY','CLAIMED','COMPLETED','FAILED','EXPIRED')),
  CONSTRAINT "github_authorization_attempts_cleanup_state_ck" CHECK (pkce_cleanup_state IN ('HELD','PENDING','COMPLETED','BLOCKED')),
  CONSTRAINT "github_authorization_attempts_cleanup_result_ck" CHECK (pkce_cleanup_result IS NULL OR pkce_cleanup_result IN ('CONFIRMED','UNAVAILABLE','SCOPE_UNAPPROVED','NOT_APPLICABLE')),
  CONSTRAINT "github_authorization_attempts_revision_ck" CHECK (expected_connection_revision >= 0),
  CONSTRAINT "github_authorization_attempts_verifier_required_ck" CHECK (phase NOT IN ('READY','CLAIMED','COMPLETED') OR pkce_secret_version_ref IS NOT NULL),
  CONSTRAINT "github_authorization_attempts_consumed_required_ck" CHECK (phase NOT IN ('CLAIMED','COMPLETED') OR consumed_at IS NOT NULL),
  CONSTRAINT "github_authorization_attempts_unconsumed_ck" CHECK (phase NOT IN ('PREPARING','READY') OR consumed_at IS NULL),
  CONSTRAINT "github_authorization_attempts_cleanup_intent_ck" CHECK (phase NOT IN ('COMPLETED','FAILED','EXPIRED') OR pkce_cleanup_state <> 'HELD'),
  CONSTRAINT "github_authorization_attempts_cleanup_complete_ck" CHECK (pkce_cleanup_state <> 'COMPLETED' OR (pkce_cleanup_result IS NOT NULL AND pkce_cleanup_result IN ('CONFIRMED','NOT_APPLICABLE'))),
  CONSTRAINT "github_authorization_attempts_object_ref_ck" CHECK (btrim(pkce_secret_object_ref) <> ''),
  CONSTRAINT "github_authorization_attempts_version_ref_ck" CHECK (pkce_secret_version_ref IS NULL OR (btrim(pkce_secret_version_ref) <> '' AND lower(btrim(pkce_secret_version_ref)) <> 'latest')),
  CONSTRAINT "github_authorization_attempts_return_ref_ck" CHECK (btrim(approved_return_ref) <> ''),
  CONSTRAINT "github_authorization_attempts_correlation_ck" CHECK (btrim(correlation_id) <> ''),
  CONSTRAINT "github_authorization_attempts_expiry_ck" CHECK (expires_at > created_at),
  CONSTRAINT "github_authorization_attempts_timestamps_ck" CHECK (updated_at >= created_at),
  CONSTRAINT "github_attempts_cleanup_terminal_ck" CHECK (pkce_cleanup_state <> 'COMPLETED' OR phase IN ('COMPLETED','FAILED','EXPIRED')),
  CONSTRAINT "github_attempts_pkce_object_owner_ck" CHECK (pkce_secret_object_ref = 'pkce:' || id::text)
);

CREATE TABLE "ingestion"."github_credential_operations" (
  "id" uuid PRIMARY KEY NOT NULL,
  "connection_id" uuid NOT NULL,
  "generation_id" uuid NOT NULL,
  "expected_connection_revision" bigint NOT NULL,
  "expected_generation_revision" bigint NOT NULL,
  "kind" text NOT NULL,
  "phase" text NOT NULL,
  "owner_id" uuid,
  "fence" bigint NOT NULL,
  "started_at" timestamptz,
  "deadline_at" timestamptz,
  "candidate_secret_version_ref" text,
  "cleanup_target_version_ref" text,
  "cleanup_scope" text,
  "upstream_result" text,
  "vault_result" text,
  "completed_at" timestamptz,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "github_credential_operations_kind_ck" CHECK (kind IN ('REFRESH','CLEANUP')),
  CONSTRAINT "github_credential_operations_phase_ck" CHECK ((kind = 'REFRESH' AND phase IN ('PENDING','CLAIMED','REQUEST_MAY_BE_IN_FLIGHT','VERSION_SAVED','COMPLETED','BLOCKED')) OR (kind = 'CLEANUP' AND phase IN ('PENDING','CLAIMED','COMPLETED','BLOCKED'))),
  CONSTRAINT "github_credential_operations_revision_ck" CHECK (fence >= 0 AND expected_connection_revision >= 0 AND expected_generation_revision >= 0),
  CONSTRAINT "github_credential_operations_claim_ck" CHECK ((phase = 'PENDING' AND owner_id IS NULL AND started_at IS NULL AND deadline_at IS NULL) OR (phase <> 'PENDING' AND owner_id IS NOT NULL AND started_at IS NOT NULL AND deadline_at IS NOT NULL AND deadline_at >= started_at)),
  CONSTRAINT "github_credential_operations_completion_ck" CHECK ((phase = 'COMPLETED') = (completed_at IS NOT NULL)),
  CONSTRAINT "github_credential_operations_kind_fields_ck" CHECK ((kind = 'REFRESH' AND cleanup_scope IS NULL AND cleanup_target_version_ref IS NULL AND upstream_result IS NULL AND vault_result IS NULL) OR (kind = 'CLEANUP' AND candidate_secret_version_ref IS NULL AND cleanup_scope IS NOT NULL AND cleanup_scope IN ('EXACT_VERSION','INVALIDATED_GENERATION') AND upstream_result IS NOT NULL AND upstream_result IN ('PENDING','CONFIRMED','UNAVAILABLE','SCOPE_UNAPPROVED','NOT_APPLICABLE') AND vault_result IS NOT NULL AND vault_result IN ('PENDING','CONFIRMED','UNAVAILABLE','SCOPE_UNAPPROVED','NOT_APPLICABLE'))),
  CONSTRAINT "github_credential_operations_cleanup_target_ck" CHECK (kind <> 'CLEANUP' OR ((cleanup_scope = 'EXACT_VERSION' AND cleanup_target_version_ref IS NOT NULL) OR (cleanup_scope = 'INVALIDATED_GENERATION' AND cleanup_target_version_ref IS NULL))),
  CONSTRAINT "github_credential_operations_exact_versions_ck" CHECK ((candidate_secret_version_ref IS NULL OR (btrim(candidate_secret_version_ref) <> '' AND lower(btrim(candidate_secret_version_ref)) <> 'latest')) AND (cleanup_target_version_ref IS NULL OR (btrim(cleanup_target_version_ref) <> '' AND lower(btrim(cleanup_target_version_ref)) <> 'latest'))),
  CONSTRAINT "github_credential_operations_candidate_version_ck" CHECK (kind <> 'REFRESH' OR phase NOT IN ('VERSION_SAVED','COMPLETED') OR candidate_secret_version_ref IS NOT NULL),
  CONSTRAINT "github_credential_operations_cleanup_complete_ck" CHECK (kind <> 'CLEANUP' OR phase <> 'COMPLETED' OR (upstream_result IN ('CONFIRMED','NOT_APPLICABLE') AND vault_result IN ('CONFIRMED','NOT_APPLICABLE'))),
  CONSTRAINT "github_credential_operations_timestamps_ck" CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX "github_connections_principal_uq" ON "ingestion"."github_connections" ("tenant_id", "entra_object_id");

CREATE UNIQUE INDEX "github_connections_membership_uq" ON "ingestion"."github_connections" ("id", "tenant_id", "entra_object_id");

CREATE UNIQUE INDEX "github_generations_membership_uq" ON "ingestion"."github_connection_generations" ("connection_id", "id");

CREATE UNIQUE INDEX "github_generations_object_ref_uq" ON "ingestion"."github_connection_generations" ("secret_object_ref");

CREATE UNIQUE INDEX "github_attempts_target_generation_uq" ON "ingestion"."github_authorization_attempts" ("target_generation_id");

CREATE UNIQUE INDEX "github_attempts_state_digest_uq" ON "ingestion"."github_authorization_attempts" ("state_digest");

CREATE UNIQUE INDEX "github_attempts_pkce_object_ref_uq" ON "ingestion"."github_authorization_attempts" ("pkce_secret_object_ref");

ALTER TABLE "ingestion"."github_connection_generations" ADD CONSTRAINT "github_generation_connection" FOREIGN KEY ("connection_id") REFERENCES "ingestion"."github_connections" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."github_connections" ADD CONSTRAINT "github_connections_active_generation_fk" FOREIGN KEY ("id", "active_generation_id") REFERENCES "ingestion"."github_connection_generations" ("connection_id", "id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."github_authorization_attempts" ADD CONSTRAINT "github_attempts_identity_fk" FOREIGN KEY ("connection_id", "tenant_id", "entra_object_id") REFERENCES "ingestion"."github_connections" ("id", "tenant_id", "entra_object_id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."github_authorization_attempts" ADD CONSTRAINT "github_attempts_expected_generation_fk" FOREIGN KEY ("connection_id", "expected_generation_id") REFERENCES "ingestion"."github_connection_generations" ("connection_id", "id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."github_authorization_attempts" ADD CONSTRAINT "github_attempts_target_generation_fk" FOREIGN KEY ("connection_id", "target_generation_id") REFERENCES "ingestion"."github_connection_generations" ("connection_id", "id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."github_credential_operations" ADD CONSTRAINT "github_operations_generation_fk" FOREIGN KEY ("connection_id", "generation_id") REFERENCES "ingestion"."github_connection_generations" ("connection_id", "id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE ingestion.github_connections ALTER CONSTRAINT github_connections_active_generation_fk DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX github_generations_one_active_uq ON ingestion.github_connection_generations (connection_id) WHERE state = 'ACTIVE';
ALTER TABLE ingestion.github_authorization_attempts ALTER CONSTRAINT github_attempts_identity_fk DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE ingestion.github_authorization_attempts ALTER CONSTRAINT github_attempts_expected_generation_fk DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE ingestion.github_authorization_attempts ALTER CONSTRAINT github_attempts_target_generation_fk DEFERRABLE INITIALLY DEFERRED;
CREATE UNIQUE INDEX github_operations_one_refresh_uq ON ingestion.github_credential_operations (generation_id) WHERE kind = 'REFRESH' AND phase <> 'COMPLETED';
ALTER TABLE ingestion.github_credential_operations ALTER CONSTRAINT github_operations_generation_fk DEFERRABLE INITIALLY DEFERRED;

-- DD-020: transactional metadata only; no remote token/PKCE material.
CREATE FUNCTION ingestion.assert_github_connection(p_connection uuid) RETURNS void
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
DECLARE current_generation uuid;
BEGIN
    SELECT active_generation_id INTO current_generation FROM ingestion.github_connections
      WHERE id = p_connection FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    IF EXISTS (SELECT 1 FROM ingestion.github_connection_generations
               WHERE connection_id = p_connection AND state = 'ACTIVE'
                 AND id IS DISTINCT FROM current_generation)
       OR (current_generation IS NOT NULL AND NOT EXISTS (
           SELECT 1 FROM ingestion.github_connection_generations
           WHERE connection_id = p_connection AND id = current_generation AND state = 'ACTIVE')) THEN
        RAISE EXCEPTION 'Connector active generation mismatch' USING ERRCODE = '23514', CONSTRAINT = 'github_active_consistency_ck';
    END IF;
END;
$$;
CREATE FUNCTION ingestion.check_github_connection() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
BEGIN
    IF TG_TABLE_NAME = 'github_connections' THEN
        IF TG_OP <> 'DELETE' THEN PERFORM ingestion.assert_github_connection(NEW.id); END IF;
    ELSE
        IF TG_OP <> 'INSERT' THEN PERFORM ingestion.assert_github_connection(OLD.connection_id); END IF;
        IF TG_OP <> 'DELETE' THEN PERFORM ingestion.assert_github_connection(NEW.connection_id); END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER github_connections_active_consistency
AFTER INSERT OR UPDATE OR DELETE ON ingestion.github_connections
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ingestion.check_github_connection();
CREATE CONSTRAINT TRIGGER github_generations_active_consistency
AFTER INSERT OR UPDATE OR DELETE ON ingestion.github_connection_generations
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ingestion.check_github_connection();

CREATE FUNCTION ingestion.guard_github_metadata() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Connector identity and creation time are immutable' USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_guard_ck';
    END IF;
    IF TG_TABLE_NAME = 'github_connections' THEN
        IF ROW(NEW.tenant_id, NEW.entra_object_id) IS DISTINCT FROM ROW(OLD.tenant_id, OLD.entra_object_id)
           OR (NEW IS DISTINCT FROM OLD AND NEW.revision <= OLD.revision) THEN
            RAISE EXCEPTION 'Connector identity or revision violation' USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_guard_ck';
        END IF;
    ELSIF TG_TABLE_NAME = 'github_connection_generations' THEN
        IF ROW(NEW.connection_id, NEW.secret_object_ref) IS DISTINCT FROM ROW(OLD.connection_id, OLD.secret_object_ref)
           OR (OLD.github_user_id IS NOT NULL AND NEW.github_user_id IS DISTINCT FROM OLD.github_user_id)
           OR (OLD.activated_at IS NOT NULL AND NEW.activated_at IS DISTINCT FROM OLD.activated_at)
           OR (OLD.state = 'INVALIDATED' AND NEW.state <> 'INVALIDATED')
           OR (NEW IS DISTINCT FROM OLD AND NEW.revision <= OLD.revision) THEN
            RAISE EXCEPTION 'Connector generation identity or revision violation' USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_guard_ck';
        END IF;
    ELSIF TG_TABLE_NAME = 'github_authorization_attempts' THEN
        IF ROW(NEW.connection_id, NEW.tenant_id, NEW.entra_object_id, NEW.expected_connection_revision,
               NEW.expected_generation_id, NEW.target_generation_id, NEW.state_digest, NEW.expires_at,
               NEW.pkce_secret_object_ref, NEW.approved_return_ref, NEW.correlation_id)
           IS DISTINCT FROM ROW(OLD.connection_id, OLD.tenant_id, OLD.entra_object_id, OLD.expected_connection_revision,
               OLD.expected_generation_id, OLD.target_generation_id, OLD.state_digest, OLD.expires_at,
               OLD.pkce_secret_object_ref, OLD.approved_return_ref, OLD.correlation_id)
           OR (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
           OR (OLD.pkce_secret_version_ref IS NOT NULL AND NEW.pkce_secret_version_ref IS DISTINCT FROM OLD.pkce_secret_version_ref)
           OR NOT (NEW.phase = OLD.phase
               OR (OLD.phase = 'PREPARING' AND NEW.phase IN ('READY','FAILED','EXPIRED'))
               OR (OLD.phase = 'READY' AND NEW.phase IN ('CLAIMED','FAILED','EXPIRED'))
               OR (OLD.phase = 'CLAIMED' AND NEW.phase IN ('COMPLETED','FAILED','EXPIRED'))) THEN
            RAISE EXCEPTION 'Connector authorization attempt is immutable or consumed' USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_guard_ck';
        END IF;
    ELSE
        IF ROW(NEW.connection_id, NEW.generation_id, NEW.kind, NEW.cleanup_scope, NEW.cleanup_target_version_ref,
               NEW.expected_connection_revision, NEW.expected_generation_revision)
           IS DISTINCT FROM ROW(OLD.connection_id, OLD.generation_id, OLD.kind, OLD.cleanup_scope, OLD.cleanup_target_version_ref,
               OLD.expected_connection_revision, OLD.expected_generation_revision)
           OR NEW.fence < OLD.fence
           OR (NEW.owner_id IS DISTINCT FROM OLD.owner_id AND NEW.fence <= OLD.fence)
           OR (OLD.candidate_secret_version_ref IS NOT NULL AND NEW.candidate_secret_version_ref IS DISTINCT FROM OLD.candidate_secret_version_ref)
           OR NOT (NEW.phase = OLD.phase
               OR (OLD.kind = 'REFRESH' AND (
                   (OLD.phase = 'PENDING' AND NEW.phase IN ('CLAIMED','BLOCKED'))
                   OR (OLD.phase = 'CLAIMED' AND NEW.phase IN ('PENDING','REQUEST_MAY_BE_IN_FLIGHT','BLOCKED'))
                   OR (OLD.phase = 'REQUEST_MAY_BE_IN_FLIGHT' AND NEW.phase IN ('VERSION_SAVED','BLOCKED'))
                   OR (OLD.phase = 'VERSION_SAVED' AND NEW.phase IN ('COMPLETED','BLOCKED'))
                   OR (OLD.phase = 'BLOCKED' AND NEW.phase = 'VERSION_SAVED')))
               OR (OLD.kind = 'CLEANUP' AND (
                   (OLD.phase = 'PENDING' AND NEW.phase IN ('CLAIMED','BLOCKED'))
                   OR (OLD.phase = 'CLAIMED' AND NEW.phase IN ('PENDING','COMPLETED','BLOCKED'))
                   OR (OLD.phase = 'BLOCKED' AND NEW.phase IN ('PENDING','CLAIMED'))))) THEN
            RAISE EXCEPTION 'Connector operation target, fence or remote phase violation' USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_guard_ck';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION ingestion.reject_github_metadata_truncate() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
    RAISE EXCEPTION 'Connector cleanup requires transactional guarded operations' USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_no_truncate_ck';
END; $$;

CREATE TRIGGER github_connections_guard BEFORE UPDATE ON ingestion.github_connections FOR EACH ROW EXECUTE FUNCTION ingestion.guard_github_metadata();
CREATE TRIGGER github_connections_no_truncate BEFORE TRUNCATE ON ingestion.github_connections FOR EACH STATEMENT EXECUTE FUNCTION ingestion.reject_github_metadata_truncate();
CREATE TRIGGER github_connection_generations_guard BEFORE UPDATE ON ingestion.github_connection_generations FOR EACH ROW EXECUTE FUNCTION ingestion.guard_github_metadata();
CREATE TRIGGER github_connection_generations_no_truncate BEFORE TRUNCATE ON ingestion.github_connection_generations FOR EACH STATEMENT EXECUTE FUNCTION ingestion.reject_github_metadata_truncate();
CREATE TRIGGER github_authorization_attempts_guard BEFORE UPDATE ON ingestion.github_authorization_attempts FOR EACH ROW EXECUTE FUNCTION ingestion.guard_github_metadata();
CREATE TRIGGER github_authorization_attempts_no_truncate BEFORE TRUNCATE ON ingestion.github_authorization_attempts FOR EACH STATEMENT EXECUTE FUNCTION ingestion.reject_github_metadata_truncate();
CREATE TRIGGER github_credential_operations_guard BEFORE UPDATE ON ingestion.github_credential_operations FOR EACH ROW EXECUTE FUNCTION ingestion.guard_github_metadata();
CREATE TRIGGER github_credential_operations_no_truncate BEFORE TRUNCATE ON ingestion.github_credential_operations FOR EACH STATEMENT EXECUTE FUNCTION ingestion.reject_github_metadata_truncate();
