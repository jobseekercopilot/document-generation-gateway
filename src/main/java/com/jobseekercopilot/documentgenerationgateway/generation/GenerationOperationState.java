package com.jobseekercopilot.documentgenerationgateway.generation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum GenerationOperationState {
    CREATED,
    SNAPSHOTS_RESOLVED,
    APPLICATION_SAVED,
    OUTPUT_READY,
    ESTIMATED,
    CREDIT_RESERVED("ALLOWANCE_RESERVED"),
    GENERATION_IN_PROGRESS,
    GENERATION_OUTCOME_UNKNOWN,
    DRAFT_GENERATED,
    DRAFTS_STORED_PENDING_CREDIT("DRAFTS_STORED_PENDING_ALLOWANCE"),
    CREDIT_COMMITTED("ALLOWANCE_COMMITTED"),
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

    private final String publicName;

    GenerationOperationState() {
        this.publicName = name();
    }

    GenerationOperationState(String publicName) {
        this.publicName = publicName;
    }

    @JsonValue
    public String publicName() {
        return publicName;
    }

    @JsonCreator
    public static GenerationOperationState fromJson(String value) {
        for (GenerationOperationState state : values()) {
            if (state.publicName.equals(value) || state.name().equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown generation operation state");
    }

    public boolean terminal() {
        return this == COMPLETED
                || this == GENERATION_OUTCOME_UNKNOWN
                || this == RECOVERY_REQUIRED
                || this == FAILED
                || this == CANCELLED;
    }
}
