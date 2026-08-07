package com.jobseekercopilot.documentgenerationgateway.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentVersionLifecycleResponse(
        UUID id,
        UUID documentFamilyId,
        DocumentKind documentType,
        int version,
        String lifecycleState,
        String retentionState,
        boolean current,
        OffsetDateTime archivedAt,
        OffsetDateTime deletedAt,
        OffsetDateTime purgeEligibleAt,
        OffsetDateTime purgedAt,
        String unavailableReason) {
}
