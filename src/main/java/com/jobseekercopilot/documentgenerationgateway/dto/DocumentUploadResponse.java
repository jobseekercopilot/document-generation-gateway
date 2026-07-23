package com.jobseekercopilot.documentgenerationgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Response containing uploaded and latest document download metadata")
public record DocumentUploadResponse(
        UUID generatedDocumentId,
        UUID applicationId,
        String cvDocumentId,
        String coverLetterDocumentId,
        Integer version,
        DownloadFileResponse uploadedFile,
        List<DownloadFileResponse> regeneratedFiles,
        DocumentDownloadsResponse latestFiles,
        String message) {
}
