package com.jobseekercopilot.documentgenerationgateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        description = "Strictly allowlisted, content-free recovery and billing outcome for one requested output.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GenerationRecoverySummaryResponse(
        @Schema(allowableValues = {"LLM", "DETERMINISTIC_FALLBACK", "NOT_AVAILABLE"})
        String generationSource,
        @Schema(allowableValues = {"APPLIED", "CHECKED", "NOT_REQUIRED"})
        String structuralRepairStatus,
        @Min(0) @Max(200) int duplicateItemsRemoved,
        @Min(0) @Max(2) int providerAttemptCount,
        @Min(0) @Max(1) int automaticRetryCount,
        boolean retried,
        @Schema(allowableValues = {"RATE_LIMITED"})
        String retryReason,
        boolean retainedResponseReplayed,
        boolean deterministicFallbackUsed,
        @Schema(allowableValues = {
                "PROVIDER_FAILURE",
                "EMPTY_PROVIDER_RESPONSE",
                "RECONCILIATION_EXHAUSTED",
                "MODEL_OUTPUT_REJECTED",
                "RETAINED_MODEL_OUTPUT_REJECTED"
        })
        String fallbackReason,
        @Schema(allowableValues = {"NOT_REQUIRED", "PENDING", "RECOVERED", "EXHAUSTED"})
        String reconciliationStatus,
        @Min(0) @Max(60) int reconciliationAttempts,
        @Schema(allowableValues = {"RETAINED_RESPONSE", "DETERMINISTIC_FALLBACK"})
        String reconciliationSource,
        @Schema(allowableValues = {
                "NOT_RESERVED",
                "RESERVED",
                "RESERVED_PENDING_RECONCILIATION",
                "COMMITTED",
                "RELEASED_NO_CHARGE",
                "RELEASED_AFTER_FAILURE",
                "RELEASED_AFTER_RECONCILIATION"
        })
        String billingStatus,
        boolean charged,
        boolean released) {
}
