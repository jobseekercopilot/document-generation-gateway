package com.jobseekercopilot.documentgenerationgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportUploadResponse(
        UUID generatedDocumentId,
        ExportFileItem uploadedFile,
        List<ExportFileItem> regeneratedFiles,
        ExportLatestFiles latestFiles,
        String message) {
}
