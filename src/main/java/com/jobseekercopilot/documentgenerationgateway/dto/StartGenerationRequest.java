package com.jobseekercopilot.documentgenerationgateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StartGenerationRequest(
        @NotNull @Size(min = 2, max = 2)
        List<@NotNull @Valid DocumentEvidenceSelection> documents) {

    public StartGenerationRequest {
        documents = documents == null ? null : List.copyOf(documents);
    }
}
