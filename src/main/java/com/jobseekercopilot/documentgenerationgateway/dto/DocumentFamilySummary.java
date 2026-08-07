package com.jobseekercopilot.documentgenerationgateway.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentFamilySummary(
        UUID documentFamilyId,
        String jobId,
        DocumentKind documentType,
        UUID latestDocumentId,
        int latestVersion,
        String latestSource,
        String latestLifecycle,
        String latestRetention,
        UUID currentDocumentId,
        Integer currentVersion,
        long versionCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
