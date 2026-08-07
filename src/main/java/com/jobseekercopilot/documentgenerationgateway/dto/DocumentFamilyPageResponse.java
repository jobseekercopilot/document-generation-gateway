package com.jobseekercopilot.documentgenerationgateway.dto;

import java.util.List;

public record DocumentFamilyPageResponse(
        List<DocumentFamilySummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
