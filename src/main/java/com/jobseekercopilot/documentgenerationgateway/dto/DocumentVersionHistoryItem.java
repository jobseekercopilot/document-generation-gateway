package com.jobseekercopilot.documentgenerationgateway.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentVersionHistoryItem(
        UUID documentId,
        int version,
        String title,
        String source,
        String lifecycle,
        String retention,
        boolean current,
        OffsetDateTime approvedAt,
        OffsetDateTime archivedAt,
        OffsetDateTime deletedAt,
        OffsetDateTime purgeEligibleAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<DocumentArtifactManifestItem> artifacts) {
}
