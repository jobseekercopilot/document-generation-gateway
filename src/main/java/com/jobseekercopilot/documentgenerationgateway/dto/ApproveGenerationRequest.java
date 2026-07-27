package com.jobseekercopilot.documentgenerationgateway.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ApproveGenerationRequest(
        @NotNull UUID cvDocumentId,
        @NotNull UUID coverLetterDocumentId) {
}
