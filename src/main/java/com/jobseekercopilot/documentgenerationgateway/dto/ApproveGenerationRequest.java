package com.jobseekercopilot.documentgenerationgateway.dto;

import java.util.UUID;

public record ApproveGenerationRequest(
        UUID cvDocumentId,
        UUID coverLetterDocumentId) {
}
