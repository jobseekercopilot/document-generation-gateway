package com.jobseekercopilot.documentgenerationgateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        description = "Content-free public state for one explicitly requested output.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GenerationOutputResultResponse(
        @Schema(allowableValues = {
                "READY", "ESTIMATED", "ALLOWANCE_RESERVED",
                "OUTCOME_UNKNOWN", "DRAFT_GENERATED",
                "STORED_PENDING_ALLOWANCE", "ALLOWANCE_COMMITTED",
                "STORED", "FAILED"
        })
        String status,
        Boolean regeneration,
        UUID documentId,
        String billingOutcome,
        Map<String, Object> outcomeReconciliation,
        String failureCode,
        String failureMessage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        GenerationRecoverySummaryResponse recoverySummary) {
}
