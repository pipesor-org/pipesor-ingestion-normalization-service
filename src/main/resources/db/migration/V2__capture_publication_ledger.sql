-- Derived capture increment from model/domain.yml 1.3.0; no grants or credentials.

CREATE TABLE "ingestion"."capture_receipts" (
  "id" uuid PRIMARY KEY NOT NULL,
  "capture_domain" text NOT NULL,
  "tenant_id" uuid NOT NULL,
  "source" text NOT NULL,
  "body_digest" text NOT NULL,
  "app_id" text NOT NULL,
  "account_id" text NOT NULL,
  "installation_id" text NOT NULL,
  "repository_id" text NOT NULL,
  "workflow_id" text NOT NULL,
  "run_id" text NOT NULL,
  "run_attempt" integer NOT NULL,
  "scope_revision" text NOT NULL,
  "correlation_id" text NOT NULL,
  "snapshot_message_id" uuid UNIQUE NOT NULL,
  "evidence_message_id" uuid UNIQUE NOT NULL,
  "phase" text NOT NULL,
  "revision" bigint NOT NULL,
  "claim_fence" bigint NOT NULL,
  "claim_owner" uuid,
  "lease_until" timestamptz,
  "observation_id" uuid,
  "blocked_reason" text,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  "prepared_at" timestamptz,
  "published_at" timestamptz,
  "replay_until" timestamptz NOT NULL,
  CONSTRAINT "capture_receipts_capture_domain_ck" CHECK (btrim(capture_domain) <> ''),
  CONSTRAINT "capture_receipts_source_ck" CHECK (btrim(source) <> ''),
  CONSTRAINT "capture_receipts_body_digest_ck" CHECK (btrim(body_digest) <> ''),
  CONSTRAINT "capture_receipts_body_digest_format_ck" CHECK (body_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT "capture_receipts_app_id_ck" CHECK (btrim(app_id) <> ''),
  CONSTRAINT "capture_receipts_app_id_format_ck" CHECK (app_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_receipts_account_id_ck" CHECK (btrim(account_id) <> ''),
  CONSTRAINT "capture_receipts_account_id_format_ck" CHECK (account_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_receipts_installation_id_ck" CHECK (btrim(installation_id) <> ''),
  CONSTRAINT "capture_receipts_installation_id_format_ck" CHECK (installation_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_receipts_repository_id_ck" CHECK (btrim(repository_id) <> ''),
  CONSTRAINT "capture_receipts_repository_id_format_ck" CHECK (repository_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_receipts_workflow_id_ck" CHECK (btrim(workflow_id) <> ''),
  CONSTRAINT "capture_receipts_workflow_id_format_ck" CHECK (workflow_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_receipts_run_id_ck" CHECK (btrim(run_id) <> ''),
  CONSTRAINT "capture_receipts_run_id_format_ck" CHECK (run_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_receipts_attempt_ck" CHECK (run_attempt > 0),
  CONSTRAINT "capture_receipts_scope_revision_ck" CHECK (btrim(scope_revision) <> ''),
  CONSTRAINT "capture_receipts_correlation_id_ck" CHECK (btrim(correlation_id) <> ''),
  CONSTRAINT "capture_receipts_phase_nonempty_ck" CHECK (btrim(phase) <> ''),
  CONSTRAINT "capture_receipts_revision_ck" CHECK (revision >= 0),
  CONSTRAINT "capture_receipts_claim_fence_ck" CHECK (claim_fence >= 0),
  CONSTRAINT "capture_receipts_timestamps_ck" CHECK (updated_at >= created_at),
  CONSTRAINT "capture_receipts_claim_ck" CHECK ((claim_owner IS NULL) = (lease_until IS NULL)),
  CONSTRAINT "capture_receipts_blocked_ck" CHECK ((phase = 'BLOCKED') = (blocked_reason IS NOT NULL) AND (blocked_reason IS NULL OR btrim(blocked_reason) <> '')),
  CONSTRAINT "capture_receipts_phase_ck" CHECK (phase IN ('ACCEPTED','PREPARED','PUBLISHED','BLOCKED')),
  CONSTRAINT "capture_receipts_message_ids_ck" CHECK (snapshot_message_id <> evidence_message_id),
  CONSTRAINT "capture_receipts_preparation_ck" CHECK ((phase NOT IN ('PREPARED','PUBLISHED') OR (observation_id IS NOT NULL AND prepared_at IS NOT NULL AND replay_until IS NOT NULL)) AND (phase <> 'ACCEPTED' OR (observation_id IS NULL AND prepared_at IS NULL AND published_at IS NULL))),
  CONSTRAINT "capture_receipts_publication_ck" CHECK ((phase = 'PUBLISHED') = (published_at IS NOT NULL)),
  CONSTRAINT "capture_receipts_lifecycle_times_ck" CHECK ((prepared_at IS NULL OR prepared_at >= created_at) AND (published_at IS NULL OR published_at >= prepared_at) AND replay_until > created_at),
  CONSTRAINT "capture_receipts_claim_phase_ck" CHECK (claim_owner IS NULL OR phase = 'ACCEPTED')
);

CREATE TABLE "ingestion"."capture_delivery_aliases" (
  "id" uuid PRIMARY KEY NOT NULL,
  "capture_domain" text NOT NULL,
  "source" text NOT NULL,
  "delivery_id" text NOT NULL,
  "receipt_id" uuid NOT NULL,
  "created_at" timestamptz NOT NULL,
  CONSTRAINT "capture_delivery_aliases_capture_domain_ck" CHECK (btrim(capture_domain) <> ''),
  CONSTRAINT "capture_delivery_aliases_source_ck" CHECK (btrim(source) <> ''),
  CONSTRAINT "capture_delivery_aliases_delivery_id_ck" CHECK (btrim(delivery_id) <> '')
);

CREATE TABLE "ingestion"."capture_observations" (
  "id" uuid PRIMARY KEY NOT NULL,
  "capture_domain" text NOT NULL,
  "source" text NOT NULL,
  "observation_key" text NOT NULL,
  "protected_content_digest" text NOT NULL,
  "origin_receipt_id" uuid NOT NULL,
  "repository_id" text NOT NULL,
  "workflow_id" text NOT NULL,
  "run_id" text NOT NULL,
  "run_attempt" integer NOT NULL,
  "observed_at" timestamptz NOT NULL,
  "observed_at_submicro" integer NOT NULL,
  "source_updated_at" timestamptz NOT NULL,
  "source_updated_at_submicro" integer NOT NULL,
  "normalizer_version" text NOT NULL,
  "protection_policy_version" text NOT NULL,
  "correlation_id" text NOT NULL,
  "required_output_count" integer NOT NULL,
  "created_at" timestamptz NOT NULL,
  CONSTRAINT "capture_observations_capture_domain_ck" CHECK (btrim(capture_domain) <> ''),
  CONSTRAINT "capture_observations_source_ck" CHECK (btrim(source) <> ''),
  CONSTRAINT "capture_observations_observation_key_ck" CHECK (observation_key ~ '^[0-9a-f]{64}$'),
  CONSTRAINT "capture_observations_protected_content_digest_ck" CHECK (btrim(protected_content_digest) <> ''),
  CONSTRAINT "capture_observations_protected_content_digest_format_ck" CHECK (protected_content_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT "capture_observations_repository_id_ck" CHECK (btrim(repository_id) <> ''),
  CONSTRAINT "capture_observations_repository_id_format_ck" CHECK (repository_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_observations_workflow_id_ck" CHECK (btrim(workflow_id) <> ''),
  CONSTRAINT "capture_observations_workflow_id_format_ck" CHECK (workflow_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_observations_run_id_ck" CHECK (btrim(run_id) <> ''),
  CONSTRAINT "capture_observations_run_id_format_ck" CHECK (run_id ~ '^[1-9][0-9]*$'),
  CONSTRAINT "capture_observations_attempt_ck" CHECK (run_attempt > 0),
  CONSTRAINT "capture_observations_normalizer_version_ck" CHECK (btrim(normalizer_version) <> ''),
  CONSTRAINT "capture_observations_protection_policy_version_ck" CHECK (btrim(protection_policy_version) <> ''),
  CONSTRAINT "capture_observations_correlation_id_ck" CHECK (btrim(correlation_id) <> ''),
  CONSTRAINT "capture_observations_plan_ck" CHECK (required_output_count IN (1,2)),
  CONSTRAINT "capture_observations_observed_at_submicro_ck" CHECK (observed_at_submicro BETWEEN 0 AND 999),
  CONSTRAINT "capture_observations_source_updated_at_submicro_ck" CHECK (source_updated_at_submicro BETWEEN 0 AND 999)
);

CREATE TABLE "ingestion"."publication_outputs" (
  "id" uuid PRIMARY KEY NOT NULL,
  "observation_id" uuid NOT NULL,
  "event_type" text NOT NULL,
  "schema_major" integer NOT NULL,
  "fragment_locator" text,
  "payload" bytea,
  "payload_digest" text NOT NULL,
  "phase" text NOT NULL,
  "revision" bigint NOT NULL,
  "claim_fence" bigint NOT NULL,
  "attempt_count" bigint NOT NULL,
  "claim_owner" uuid,
  "lease_until" timestamptz,
  "next_attempt_at" timestamptz,
  "last_attempt_at" timestamptz,
  "confirmed_at" timestamptz,
  "payload_removed_at" timestamptz,
  "payload_recover_until" timestamptz,
  "blocked_reason" text,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  CONSTRAINT "publication_outputs_event_type_ck" CHECK (btrim(event_type) <> ''),
  CONSTRAINT "publication_outputs_payload_digest_ck" CHECK (btrim(payload_digest) <> ''),
  CONSTRAINT "publication_outputs_payload_digest_format_ck" CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT "publication_outputs_phase_nonempty_ck" CHECK (btrim(phase) <> ''),
  CONSTRAINT "publication_outputs_revision_ck" CHECK (revision >= 0),
  CONSTRAINT "publication_outputs_claim_fence_ck" CHECK (claim_fence >= 0),
  CONSTRAINT "publication_outputs_attempt_count_ck" CHECK (attempt_count >= 0),
  CONSTRAINT "publication_outputs_timestamps_ck" CHECK (updated_at >= created_at),
  CONSTRAINT "publication_outputs_claim_ck" CHECK ((claim_owner IS NULL) = (lease_until IS NULL)),
  CONSTRAINT "publication_outputs_blocked_ck" CHECK ((phase = 'BLOCKED') = (blocked_reason IS NOT NULL) AND (blocked_reason IS NULL OR btrim(blocked_reason) <> '')),
  CONSTRAINT "publication_outputs_contract_ck" CHECK ((event_type = 'execution.snapshot' AND schema_major = 1 AND fragment_locator IS NULL) OR (event_type = 'protected_workflow_evidence' AND schema_major = 2 AND fragment_locator IS NOT NULL AND btrim(fragment_locator) <> '')),
  CONSTRAINT "publication_outputs_phase_ck" CHECK (phase IN ('PENDING','CLAIMED','CONFIRMED','BLOCKED')),
  CONSTRAINT "publication_outputs_claim_phase_ck" CHECK ((phase = 'CLAIMED') = (claim_owner IS NOT NULL)),
  CONSTRAINT "publication_outputs_confirmed_ck" CHECK ((phase = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
  CONSTRAINT "publication_outputs_payload_ck" CHECK ((payload IS NOT NULL AND payload_removed_at IS NULL) OR (payload IS NULL AND phase = 'CONFIRMED' AND payload_removed_at IS NOT NULL AND payload_recover_until IS NOT NULL AND payload_removed_at >= payload_recover_until)),
  CONSTRAINT "publication_outputs_lifecycle_times_ck" CHECK ((confirmed_at IS NULL OR confirmed_at >= created_at) AND (payload_removed_at IS NULL OR payload_removed_at >= confirmed_at) AND (payload_recover_until IS NULL OR payload_recover_until > created_at)),
  CONSTRAINT "publication_outputs_claim_time_ck" CHECK (phase <> 'CLAIMED' OR (last_attempt_at IS NOT NULL AND lease_until > last_attempt_at AND attempt_count > 0))
);

CREATE UNIQUE INDEX "capture_receipts_body_uq" ON "ingestion"."capture_receipts" ("capture_domain", "source", "body_digest");

CREATE UNIQUE INDEX "capture_receipts_membership_uq" ON "ingestion"."capture_receipts" ("capture_domain", "source", "id");

CREATE UNIQUE INDEX "capture_aliases_delivery_uq" ON "ingestion"."capture_delivery_aliases" ("capture_domain", "source", "delivery_id");

CREATE UNIQUE INDEX "capture_observations_key_uq" ON "ingestion"."capture_observations" ("capture_domain", "source", "observation_key");

CREATE UNIQUE INDEX "capture_observations_membership_uq" ON "ingestion"."capture_observations" ("capture_domain", "source", "id");

ALTER TABLE "ingestion"."capture_receipts" ADD CONSTRAINT "capture_receipts_observation_fk" FOREIGN KEY ("capture_domain", "source", "observation_id") REFERENCES "ingestion"."capture_observations" ("capture_domain", "source", "id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."capture_delivery_aliases" ADD CONSTRAINT "capture_aliases_receipt_fk" FOREIGN KEY ("capture_domain", "source", "receipt_id") REFERENCES "ingestion"."capture_receipts" ("capture_domain", "source", "id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."capture_observations" ADD CONSTRAINT "capture_observations_origin_fk" FOREIGN KEY ("capture_domain", "source", "origin_receipt_id") REFERENCES "ingestion"."capture_receipts" ("capture_domain", "source", "id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ingestion"."publication_outputs" ADD CONSTRAINT "publication_outputs_observation_fk" FOREIGN KEY ("observation_id") REFERENCES "ingestion"."capture_observations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE ingestion.capture_receipts ALTER CONSTRAINT capture_receipts_observation_fk DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ingestion.capture_delivery_aliases ALTER CONSTRAINT capture_aliases_receipt_fk DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ingestion.capture_observations ALTER CONSTRAINT capture_observations_origin_fk DEFERRABLE INITIALLY DEFERRED;

CREATE UNIQUE INDEX publication_outputs_snapshot_uq ON ingestion.publication_outputs (observation_id) WHERE event_type = 'execution.snapshot';

CREATE UNIQUE INDEX publication_outputs_fragment_uq ON ingestion.publication_outputs (observation_id, fragment_locator) WHERE event_type = 'protected_workflow_evidence';

ALTER TABLE ingestion.publication_outputs ALTER CONSTRAINT publication_outputs_observation_fk DEFERRABLE INITIALLY DEFERRED;

-- DD-021: identities, context, final bytes and the complete publication plan are immutable.
CREATE FUNCTION ingestion.assert_capture_observation(p_observation uuid) RETURNS void
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
DECLARE o ingestion.capture_observations; r ingestion.capture_receipts;
BEGIN
    SELECT * INTO o FROM ingestion.capture_observations WHERE id = p_observation FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT * INTO r FROM ingestion.capture_receipts WHERE id = o.origin_receipt_id;
    IF NOT FOUND OR r.observation_id IS DISTINCT FROM o.id OR r.prepared_at IS NULL
       OR ROW(o.capture_domain,o.source,o.repository_id,o.workflow_id,o.run_id,o.run_attempt,o.correlation_id)
       IS DISTINCT FROM ROW(r.capture_domain,r.source,r.repository_id,r.workflow_id,r.run_id,r.run_attempt,r.correlation_id) THEN
        RAISE EXCEPTION 'Capture observation origin mismatch' USING ERRCODE = '23514', CONSTRAINT = 'capture_origin_ck';
    END IF;
    IF EXISTS (SELECT 1 FROM ingestion.capture_receipts linked WHERE linked.observation_id = o.id AND
       ROW(linked.capture_domain,linked.source,linked.tenant_id,linked.app_id,linked.account_id,linked.installation_id,
           linked.repository_id,linked.workflow_id,linked.run_id,linked.run_attempt)
       IS DISTINCT FROM ROW(r.capture_domain,r.source,r.tenant_id,r.app_id,r.account_id,r.installation_id,
           r.repository_id,r.workflow_id,r.run_id,r.run_attempt)) THEN
        RAISE EXCEPTION 'Capture receipt context mismatch' USING ERRCODE = '23514', CONSTRAINT = 'capture_context_ck';
    END IF;
    IF (SELECT count(*) FROM ingestion.publication_outputs WHERE observation_id = o.id) <> o.required_output_count
       OR (SELECT count(*) FROM ingestion.publication_outputs WHERE observation_id = o.id AND event_type = 'execution.snapshot') <> 1
       OR NOT EXISTS (SELECT 1 FROM ingestion.publication_outputs WHERE observation_id = o.id
                      AND event_type = 'execution.snapshot' AND id = r.snapshot_message_id)
       OR (o.required_output_count > 1 AND NOT EXISTS (
           SELECT 1 FROM ingestion.publication_outputs WHERE observation_id = o.id
           AND event_type = 'protected_workflow_evidence' AND id = r.evidence_message_id)) THEN
        RAISE EXCEPTION 'Capture publication plan incomplete or message identity mismatch'
          USING ERRCODE = '23514', CONSTRAINT = 'capture_plan_ck';
    END IF;
    IF EXISTS (SELECT 1 FROM ingestion.capture_receipts WHERE observation_id = o.id AND phase = 'PUBLISHED')
       AND EXISTS (SELECT 1 FROM ingestion.publication_outputs WHERE observation_id = o.id AND phase <> 'CONFIRMED') THEN
        RAISE EXCEPTION 'Capture publication is not confirmed' USING ERRCODE = '23514', CONSTRAINT = 'capture_publication_ck';
    END IF;
END;
$$;
CREATE FUNCTION ingestion.check_capture_plan() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
BEGIN
    IF TG_TABLE_NAME = 'capture_observations' THEN
        PERFORM ingestion.assert_capture_observation(NEW.id);
    ELSIF NEW.observation_id IS NOT NULL THEN
        PERFORM ingestion.assert_capture_observation(NEW.observation_id);
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER capture_receipts_plan AFTER INSERT OR UPDATE ON ingestion.capture_receipts
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ingestion.check_capture_plan();
CREATE CONSTRAINT TRIGGER capture_observations_plan AFTER INSERT ON ingestion.capture_observations
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ingestion.check_capture_plan();
CREATE CONSTRAINT TRIGGER publication_outputs_plan AFTER INSERT OR UPDATE ON ingestion.publication_outputs
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ingestion.check_capture_plan();

CREATE FUNCTION ingestion.guard_capture_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$ BEGIN
    RAISE EXCEPTION 'Capture ledger mutation requires an approved retention operation'
      USING ERRCODE = '23514', CONSTRAINT = TG_TABLE_NAME || '_immutable_ck';
END; $$;

CREATE FUNCTION ingestion.guard_capture_receipt() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.phase <> 'ACCEPTED' OR NEW.revision <> 0 OR NEW.claim_fence <> 0
           OR NEW.claim_owner IS NOT NULL OR NEW.updated_at <> NEW.created_at THEN
            RAISE EXCEPTION 'Capture receipt requires initial accepted state' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.observation_id IS NULL AND NEW.observation_id IS NOT NULL AND NEW.phase <> 'PREPARED' THEN
        RAISE EXCEPTION 'Capture observation association requires preparation'
          USING ERRCODE = '23514', CONSTRAINT = 'capture_receipts_association_ck';
    END IF;
    IF NEW.replay_until IS DISTINCT FROM OLD.replay_until THEN
        RAISE EXCEPTION 'Capture replay horizon is immutable after acceptance'
          USING ERRCODE = '23514', CONSTRAINT = 'capture_receipts_replay_horizon_ck';
    END IF;
    IF ROW(NEW.id,NEW.capture_domain,NEW.tenant_id,NEW.source,NEW.body_digest,NEW.app_id,NEW.account_id,NEW.installation_id,
           NEW.repository_id,NEW.workflow_id,NEW.run_id,NEW.run_attempt,NEW.scope_revision,NEW.correlation_id,
           NEW.snapshot_message_id,NEW.evidence_message_id,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id,OLD.capture_domain,OLD.tenant_id,OLD.source,OLD.body_digest,OLD.app_id,OLD.account_id,OLD.installation_id,
           OLD.repository_id,OLD.workflow_id,OLD.run_id,OLD.run_attempt,OLD.scope_revision,OLD.correlation_id,
           OLD.snapshot_message_id,OLD.evidence_message_id,OLD.created_at)
       OR (OLD.blocked_reason IS NOT NULL AND NEW.blocked_reason IS DISTINCT FROM OLD.blocked_reason)
       OR NEW.revision <> OLD.revision + 1 OR NEW.updated_at < OLD.updated_at
       OR NEW.claim_fence < OLD.claim_fence
       OR (NEW.claim_owner IS NOT NULL AND (NEW.claim_owner IS DISTINCT FROM OLD.claim_owner OR NEW.lease_until IS DISTINCT FROM OLD.lease_until)
           AND NEW.claim_fence <> OLD.claim_fence + 1)
       OR (NEW.claim_owner IS NULL AND NEW.claim_fence <> OLD.claim_fence)
       OR (NEW.claim_owner IS NOT NULL AND (NEW.lease_until <= NEW.updated_at
           OR (NEW.claim_fence > OLD.claim_fence AND OLD.claim_owner IS NOT NULL AND OLD.lease_until > NEW.updated_at)))
       OR (OLD.observation_id IS NOT NULL AND NEW.observation_id IS DISTINCT FROM OLD.observation_id)
       OR (OLD.prepared_at IS NOT NULL AND ROW(NEW.prepared_at,NEW.replay_until) IS DISTINCT FROM ROW(OLD.prepared_at,OLD.replay_until))
       OR (OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at)
       OR NOT (NEW.phase = OLD.phase OR (OLD.phase = 'ACCEPTED' AND NEW.phase IN ('PREPARED','BLOCKED'))
            OR (OLD.phase = 'PREPARED' AND NEW.phase IN ('PUBLISHED','BLOCKED')))
       OR (OLD.phase = 'ACCEPTED' AND NEW.phase = 'PREPARED' AND
           (OLD.claim_owner IS NULL OR OLD.lease_until <= NEW.updated_at OR NEW.claim_owner IS NOT NULL)) THEN
        RAISE EXCEPTION 'Capture receipt identity, revision, claim or phase violation'
          USING ERRCODE = '23514', CONSTRAINT = 'capture_receipts_guard_ck';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION ingestion.guard_publication_output() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ingestion AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.phase <> 'PENDING' OR NEW.revision <> 0 OR NEW.claim_fence <> 0 OR NEW.attempt_count <> 0
           OR NEW.payload IS NULL OR NEW.payload_removed_at IS NOT NULL OR NEW.claim_owner IS NOT NULL
           OR NEW.last_attempt_at IS NOT NULL OR NEW.updated_at <> NEW.created_at THEN
            RAISE EXCEPTION 'Publication output requires initial pending bytes' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.observation_id,NEW.event_type,NEW.schema_major,NEW.fragment_locator,NEW.payload_digest,
           NEW.payload_recover_until,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id,OLD.observation_id,OLD.event_type,OLD.schema_major,OLD.fragment_locator,OLD.payload_digest,
           OLD.payload_recover_until,OLD.created_at)
       OR (OLD.blocked_reason IS NOT NULL AND NEW.blocked_reason IS DISTINCT FROM OLD.blocked_reason)
       OR NEW.revision <> OLD.revision + 1 OR NEW.updated_at < OLD.updated_at
       OR NEW.claim_fence < OLD.claim_fence OR NEW.attempt_count < OLD.attempt_count
       OR (OLD.confirmed_at IS NOT NULL AND NEW.confirmed_at IS DISTINCT FROM OLD.confirmed_at)
       OR (OLD.payload IS NULL AND NEW.payload IS DISTINCT FROM OLD.payload)
       OR (NEW.payload IS NOT NULL AND NEW.payload IS DISTINCT FROM OLD.payload)
       OR (OLD.payload_removed_at IS NOT NULL AND NEW.payload_removed_at IS DISTINCT FROM OLD.payload_removed_at)
       OR (OLD.payload IS NOT NULL AND NEW.payload IS NULL AND
           (OLD.phase <> 'CONFIRMED' OR OLD.payload_recover_until IS NULL
            OR NEW.updated_at < OLD.payload_recover_until OR NEW.payload_removed_at IS DISTINCT FROM NEW.updated_at))
       OR NOT (NEW.phase = OLD.phase OR (OLD.phase = 'PENDING' AND NEW.phase IN ('CLAIMED','BLOCKED'))
            OR (OLD.phase = 'CLAIMED' AND NEW.phase IN ('PENDING','CONFIRMED','BLOCKED')))
       OR (NEW.phase = 'CLAIMED' AND
           (NEW.claim_fence <> OLD.claim_fence + 1 OR NEW.attempt_count <> OLD.attempt_count + 1
            OR NEW.last_attempt_at IS DISTINCT FROM NEW.updated_at
            OR (OLD.phase = 'CLAIMED' AND OLD.lease_until > NEW.updated_at)))
       OR (NEW.phase <> 'CLAIMED' AND
           (NEW.claim_fence <> OLD.claim_fence OR NEW.attempt_count <> OLD.attempt_count
            OR NEW.last_attempt_at IS DISTINCT FROM OLD.last_attempt_at))
       OR (OLD.phase = 'CLAIMED' AND NEW.phase <> 'CLAIMED' AND OLD.lease_until <= NEW.updated_at) THEN
        RAISE EXCEPTION 'Publication output identity, bytes, revision, claim or phase violation'
          USING ERRCODE = '23514', CONSTRAINT = 'publication_outputs_guard_ck';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER capture_receipts_guard BEFORE INSERT OR UPDATE ON ingestion.capture_receipts
FOR EACH ROW EXECUTE FUNCTION ingestion.guard_capture_receipt();
CREATE TRIGGER publication_outputs_guard BEFORE INSERT OR UPDATE ON ingestion.publication_outputs
FOR EACH ROW EXECUTE FUNCTION ingestion.guard_publication_output();

CREATE TRIGGER capture_receipts_immutable BEFORE DELETE OR TRUNCATE ON ingestion.capture_receipts FOR EACH STATEMENT EXECUTE FUNCTION ingestion.guard_capture_immutable();
CREATE TRIGGER capture_delivery_aliases_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON ingestion.capture_delivery_aliases FOR EACH STATEMENT EXECUTE FUNCTION ingestion.guard_capture_immutable();
CREATE TRIGGER capture_observations_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON ingestion.capture_observations FOR EACH STATEMENT EXECUTE FUNCTION ingestion.guard_capture_immutable();
CREATE TRIGGER publication_outputs_immutable BEFORE DELETE OR TRUNCATE ON ingestion.publication_outputs FOR EACH STATEMENT EXECUTE FUNCTION ingestion.guard_capture_immutable();

