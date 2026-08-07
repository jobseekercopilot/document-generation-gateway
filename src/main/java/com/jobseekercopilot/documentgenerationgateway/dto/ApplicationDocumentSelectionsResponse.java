package com.jobseekercopilot.documentgenerationgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationDocumentSelectionsResponse(
        UUID id,
        String status,
        long version,
        UUID cvDocumentId,
        UUID coverLetterDocumentId,
        ApplicationDocumentSelectionReference cvDocumentReference,
        ApplicationDocumentSelectionReference coverLetterDocumentReference,
        ApplicationDocumentSelectionReference applicationUsedCvDocumentReference,
        ApplicationFrozenDocumentSelectionState applicationUsedCvState,
        ApplicationDocumentSelectionReference
                applicationUsedCoverLetterDocumentReference,
        ApplicationFrozenDocumentSelectionState applicationUsedCoverLetterState,
        LocalDateTime applicationUsedAt,
        LocalDateTime appliedAt) {
    public ApplicationDocumentSelectionsResponse(
            UUID id,
            String status,
            long version,
            UUID cvDocumentId,
            UUID coverLetterDocumentId,
            ApplicationDocumentSelectionReference cvDocumentReference,
            ApplicationDocumentSelectionReference coverLetterDocumentReference) {
        this(
                id,
                status,
                version,
                cvDocumentId,
                coverLetterDocumentId,
                cvDocumentReference,
                coverLetterDocumentReference,
                null,
                ApplicationFrozenDocumentSelectionState.UNKNOWN,
                null,
                ApplicationFrozenDocumentSelectionState.UNKNOWN,
                null,
                null);
    }
}
