package com.jobseekercopilot.documentgenerationgateway.dto;

import com.jobseekercopilot.documentgenerationgateway.generation.GenerationOperationState;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record GenerationOperationResponse(
        UUID operationId,
        UUID savedJobId,
        GenerationOperationState state,
        boolean replaySafe,
        boolean manualActionRequired,
        UUID cvDocumentId,
        UUID coverLetterDocumentId,
        UUID applicationId,
        Map<String, Object> downloads,
        String failureCode,
        String failureMessage,
        Instant deadlineAt,
        Instant createdAt,
        Instant updatedAt) {
}
