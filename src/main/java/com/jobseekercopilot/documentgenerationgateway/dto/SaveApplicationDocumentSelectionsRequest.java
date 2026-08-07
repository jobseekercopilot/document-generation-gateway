package com.jobseekercopilot.documentgenerationgateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SaveApplicationDocumentSelectionsRequest(
        @NotNull @Valid ApplicationDocumentSelectionSlotRequest cvSelection,
        @NotNull @Valid ApplicationDocumentSelectionSlotRequest coverLetterSelection,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
