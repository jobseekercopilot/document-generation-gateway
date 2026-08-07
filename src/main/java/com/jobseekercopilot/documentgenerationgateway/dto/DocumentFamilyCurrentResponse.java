package com.jobseekercopilot.documentgenerationgateway.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentFamilyCurrentResponse(
        UUID commandId,
        UUID documentFamilyId,
        UUID currentDocumentId,
        int currentVersion,
        OffsetDateTime changedAt) {
}
