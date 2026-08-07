package com.jobseekercopilot.documentgenerationgateway.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentArtifactManifestItem(
        UUID artifactId,
        String role,
        UploadFormat format,
        String source,
        String availability,
        long size,
        OffsetDateTime storedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
