package com.jobseekercopilot.documentgenerationgateway.dto;

import java.util.List;
import java.util.UUID;

public record DocumentApplicationAssociationsResponse(
        UUID documentId,
        int associationCount,
        List<DocumentApplicationAssociation> associations) {
}
