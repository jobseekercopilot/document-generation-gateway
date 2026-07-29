package com.jobseekercopilot.documentgenerationgateway.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record DocumentEvidenceSelection(
        @NotNull DocumentPurpose purpose,
        @NotEmpty @Size(max = 50) List<@NotNull UUID> entryIds,
        @NotEmpty @Size(max = 9) List<@NotNull EvidenceSection> sectionOrder) {

    public DocumentEvidenceSelection {
        entryIds = entryIds == null ? null : List.copyOf(entryIds);
        sectionOrder = sectionOrder == null ? null : List.copyOf(sectionOrder);
    }
}
