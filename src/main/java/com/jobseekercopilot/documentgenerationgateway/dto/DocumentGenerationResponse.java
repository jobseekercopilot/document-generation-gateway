package com.jobseekercopilot.documentgenerationgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Generated application documents and their exported download metadata")
public record DocumentGenerationResponse(
        String applicationId,
        String cvDocumentId,
        String coverLetterDocumentId,
        GenerationDownloadsResponse downloads) {}
