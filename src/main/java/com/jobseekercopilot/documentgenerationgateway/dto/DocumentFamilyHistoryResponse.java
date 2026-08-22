package com.jobseekercopilot.documentgenerationgateway.dto;

import java.util.List;
import java.util.UUID;

public record DocumentFamilyHistoryResponse(
        UUID documentFamilyId,
        String jobId,
        DocumentKind documentType,
        UUID currentDocumentId,
        Integer currentVersion,
        List<DocumentVersionHistoryItem> versions) {
}
