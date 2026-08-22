package com.jobseekercopilot.documentgenerationgateway.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SelectFamilyCurrentRequest(
        @NotNull UUID documentId,
        @NotNull ExpectedCurrentState expectedCurrentState,
        UUID expectedCurrentDocumentId) {
    public enum ExpectedCurrentState {
        NONE,
        SELECTED
    }
}
