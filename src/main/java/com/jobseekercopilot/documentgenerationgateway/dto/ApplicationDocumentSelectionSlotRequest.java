package com.jobseekercopilot.documentgenerationgateway.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ApplicationDocumentSelectionSlotRequest(
        @NotNull ApplicationDocumentSelectionState state,
        UUID documentId) {
}
