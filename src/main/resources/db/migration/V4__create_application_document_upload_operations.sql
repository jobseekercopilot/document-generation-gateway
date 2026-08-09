CREATE TABLE application_document_upload_operations (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    application_id UUID NOT NULL,
    job_id VARCHAR(255) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    store_operation_id UUID,
    store_state VARCHAR(32),
    document_id UUID,
    application_version BIGINT,
    failure_code VARCHAR(64),
    failure_message VARCHAR(300),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_application_upload_idempotency
        UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_application_upload_lookup
    ON application_document_upload_operations (owner_id, application_id, document_type);

CREATE INDEX idx_application_upload_recovery
    ON application_document_upload_operations (state, updated_at);
