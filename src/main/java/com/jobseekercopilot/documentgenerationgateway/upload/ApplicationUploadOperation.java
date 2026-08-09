package com.jobseekercopilot.documentgenerationgateway.upload;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import java.time.Instant;
import java.util.UUID;

public record ApplicationUploadOperation(
        UUID id,
        String ownerId,
        String idempotencyKey,
        UUID applicationId,
        String jobId,
        DocumentKind documentType,
        UploadFormat fileType,
        String requestFingerprint,
        String payloadSha256,
        ApplicationUploadState state,
        UUID storeOperationId,
        String storeState,
        UUID documentId,
        Long applicationVersion,
        String failureCode,
        String failureMessage,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
