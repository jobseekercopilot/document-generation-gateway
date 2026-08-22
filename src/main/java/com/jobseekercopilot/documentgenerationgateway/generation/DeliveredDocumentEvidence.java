package com.jobseekercopilot.documentgenerationgateway.generation;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentPurpose;
import java.util.UUID;

public record DeliveredDocumentEvidence(
        UUID documentId,
        DocumentPurpose documentType) {}
