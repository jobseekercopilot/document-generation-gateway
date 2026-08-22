package com.jobseekercopilot.documentgenerationgateway.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentApplicationAssociation(
        UUID applicationId,
        DocumentKind documentType,
        String associationState,
        String applicationStatus,
        LocalDateTime frozenAt) {
}
