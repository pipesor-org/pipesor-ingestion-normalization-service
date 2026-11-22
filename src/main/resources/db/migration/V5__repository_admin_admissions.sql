-- Repository administrator self-service admission (ADR-024) next to the ADR-023 operator import.
-- An admission records how it was granted and, when GitHub no longer lists the repository in its
-- installation, when and by whose reconciliation it was revoked. Revoked rows stay for audit.
ALTER TABLE ingestion.repository_admissions
  ADD COLUMN source text NOT NULL DEFAULT 'operator',
  ADD COLUMN revoked_at timestamptz,
  ADD COLUMN revoked_by text,
  ADD CONSTRAINT repository_admissions_source_ck CHECK (source IN ('operator', 'repository_administrator')),
  ADD CONSTRAINT repository_admissions_revoked_ck CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)
    AND (revoked_by IS NULL OR btrim(revoked_by) <> ''));
