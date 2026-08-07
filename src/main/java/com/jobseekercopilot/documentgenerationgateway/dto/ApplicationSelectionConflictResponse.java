package com.jobseekercopilot.documentgenerationgateway.dto;

public record ApplicationSelectionConflictResponse(
        int status,
        String message,
        ApplicationDocumentSelectionsResponse currentApplication) {
}
