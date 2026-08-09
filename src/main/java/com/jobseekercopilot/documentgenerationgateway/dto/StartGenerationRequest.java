package com.jobseekercopilot.documentgenerationgateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record StartGenerationRequest(
        @Size(min = 1, max = 2)
        Set<@NotNull DocumentPurpose> outputs,
        @NotNull @Size(min = 1, max = 2)
        List<@NotNull @Valid DocumentEvidenceSelection> documents) {

    public StartGenerationRequest {
        outputs = outputs == null
                ? null
                : java.util.Collections.unmodifiableSet(
                        new LinkedHashSet<>(outputs));
        documents = documents == null ? null : List.copyOf(documents);
    }

    public StartGenerationRequest(List<DocumentEvidenceSelection> documents) {
        this(null, documents);
    }
}
