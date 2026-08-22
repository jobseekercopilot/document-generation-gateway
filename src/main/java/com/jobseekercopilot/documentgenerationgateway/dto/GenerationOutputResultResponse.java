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
