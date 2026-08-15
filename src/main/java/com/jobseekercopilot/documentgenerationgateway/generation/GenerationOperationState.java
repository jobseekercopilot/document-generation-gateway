package com.jobseekercopilot.documentgenerationgateway.generation;

public enum GenerationOperationState {
    CREATED,
    SNAPSHOTS_RESOLVED,
    APPLICATION_SAVED,
    OUTPUT_READY,
    ESTIMATED,
    CREDIT_RESERVED,
    GENERATION_IN_PROGRESS,
    GENERATION_OUTCOME_UNKNOWN,
    DRAFT_GENERATED,
    DRAFTS_STORED_PENDING_CREDIT,
    CREDIT_COMMITTED,
    DRAFTS_STORED,
    AWAITING_APPROVAL,
    APPROVED,
    CV_EXPORT_IN_PROGRESS,
    CV_EXPORTED,
    COVER_LETTER_EXPORT_IN_PROGRESS,
    EXPORTED,
    COMPLETED,
    RECOVERY_REQUIRED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED
                || this == GENERATION_OUTCOME_UNKNOWN
                || this == RECOVERY_REQUIRED
                || this == FAILED
                || this == CANCELLED;
    }
}
