package com.jobseekercopilot.documentgenerationgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationDocumentSelectionReference(
        UUID documentId,
        UUID documentFamilyId,
        String jobId,
        DocumentKind documentType,
        int version,
        String groundingState,
        UUID parentDocumentId,
        Integer parentDocumentVersion) {
}
