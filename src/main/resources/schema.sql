CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS job (
    id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'VALIDATING', 'READY', 'CALCULATING', 'SUCCEEDED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED')),
    failure_stage VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    artifacts_purged_at TIMESTAMPTZ,
    artifact_cleanup_attempted_at TIMESTAMPTZ,
    received_bytes BIGINT NOT NULL DEFAULT 0 CHECK (received_bytes >= 0),
    feature_count BIGINT NOT NULL DEFAULT 0 CHECK (feature_count >= 0),
    counts_by_type JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_code VARCHAR(128),
    error_message TEXT,
    error_feature_id TEXT,
    error_field_path TEXT,
    calculation_bbox JSONB,
    calculation_result JSONB
);

ALTER TABLE job ADD COLUMN IF NOT EXISTS artifacts_purged_at TIMESTAMPTZ;
ALTER TABLE job ADD COLUMN IF NOT EXISTS artifact_cleanup_attempted_at TIMESTAMPTZ;
ALTER TABLE job ADD COLUMN IF NOT EXISTS calculation_bbox JSONB;
ALTER TABLE job ADD COLUMN IF NOT EXISTS calculation_result JSONB;

CREATE INDEX IF NOT EXISTS job_status_updated_idx ON job (status, updated_at);

CREATE TABLE IF NOT EXISTS job_feature (
    job_id UUID NOT NULL,
    id_kind VARCHAR(8) NOT NULL CHECK (id_kind IN ('number', 'string')),
    id_value TEXT NOT NULL,
    object_type TEXT NOT NULL,
    properties JSONB NOT NULL,
    geom geometry(Geometry, 32637) NOT NULL,
    PRIMARY KEY (job_id, id_kind, id_value)
);

CREATE INDEX IF NOT EXISTS job_feature_geom_gix ON job_feature USING GIST (geom);
CREATE INDEX IF NOT EXISTS job_feature_job_id_idx ON job_feature (job_id);

CREATE TABLE IF NOT EXISTS job_result_feature (
    job_id UUID NOT NULL,
    variant_id TEXT NOT NULL,
    id_kind VARCHAR(8) NOT NULL CHECK (id_kind IN ('number', 'string')),
    id_value TEXT NOT NULL,
    object_type TEXT NOT NULL,
    properties JSONB NOT NULL,
    geom geometry(Geometry, 32637) NOT NULL,
    PRIMARY KEY (job_id, variant_id, id_kind, id_value)
);

CREATE INDEX IF NOT EXISTS job_result_feature_geom_gix ON job_result_feature USING GIST (geom);
