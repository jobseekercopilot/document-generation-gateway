ALTER TABLE generation_operations
    DROP CONSTRAINT uq_generation_operation_saved_job;

CREATE INDEX idx_generation_operations_owner_saved_job
    ON generation_operations (owner_id, saved_job_id, created_at);
