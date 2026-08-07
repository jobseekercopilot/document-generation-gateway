package com.jobseekercopilot.documentgenerationgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationDocumentSelectionsResponse(
        UUID id,
        String status,
        long version,
        UUID cvDocumentId,
        UUID coverLetterDocumentId,
        ApplicationDocumentSelectionReference cvDocumentReference,
        ApplicationDocumentSelectionReference coverLetterDocumentReference) {
}
