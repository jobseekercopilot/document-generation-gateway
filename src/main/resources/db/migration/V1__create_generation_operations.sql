CREATE TABLE generation_operations (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    saved_job_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(64) NOT NULL,
    data_json TEXT NOT NULL,
    failure_code VARCHAR(64),
    failure_message VARCHAR(500),
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_generation_operation_idempotency
        UNIQUE (owner_id, idempotency_key),
    CONSTRAINT uq_generation_operation_saved_job
        UNIQUE (owner_id, saved_job_id)
);

CREATE INDEX idx_generation_operations_recovery
    ON generation_operations (state, lease_until, updated_at);
