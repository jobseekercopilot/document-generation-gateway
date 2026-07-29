DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM generation_operations
         WHERE state NOT IN (
             'COMPLETED',
             'GENERATION_OUTCOME_UNKNOWN',
             'RECOVERY_REQUIRED',
             'FAILED',
             'CANCELLED'
         )
         GROUP BY owner_id, saved_job_id, request_fingerprint
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Active generation fingerprint duplicates require explicit reconciliation before migration';
    END IF;
END
$migration$;

CREATE UNIQUE INDEX uq_generation_operation_active_fingerprint
    ON generation_operations (
        owner_id,
        saved_job_id,
        request_fingerprint
    )
    WHERE state NOT IN (
        'COMPLETED',
        'GENERATION_OUTCOME_UNKNOWN',
        'RECOVERY_REQUIRED',
        'FAILED',
        'CANCELLED'
    );
