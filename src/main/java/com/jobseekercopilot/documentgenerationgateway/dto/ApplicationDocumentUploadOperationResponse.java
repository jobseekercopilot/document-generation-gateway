package com.jobseekercopilot.documentgenerationgateway.dto;

import com.jobseekercopilot.documentgenerationgateway.upload.ApplicationUploadState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Content-free status of one owner-scoped application upload/link operation")
public record ApplicationDocumentUploadOperationResponse(
        UUID operationId,
        UUID applicationId,
        String jobId,
        DocumentKind documentType,
        UploadFormat fileType,
        ApplicationUploadState state,
        String documentStoreState,
        UUID documentId,
        Long applicationVersion,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt) {
}
