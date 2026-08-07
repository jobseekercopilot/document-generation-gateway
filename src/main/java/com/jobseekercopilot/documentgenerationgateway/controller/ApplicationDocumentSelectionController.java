package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationSelectionConflictResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.SaveApplicationDocumentSelectionsRequest;
import com.jobseekercopilot.documentgenerationgateway.service.ApplicationDocumentSelectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-generation/applications")
@SecurityRequirement(name = "bearerAuth")
public class ApplicationDocumentSelectionController {
    private final ApplicationDocumentSelectionService service;

    public ApplicationDocumentSelectionController(
            ApplicationDocumentSelectionService service) {
        this.service = service;
    }

    @PutMapping("/{applicationId}/document-selections")
    @Operation(
            summary = "Atomically save the complete optional document selection",
            description = "Both slots must explicitly be SELECTED or OMITTED. The exact desired state is owner-scoped, expected-version protected and replay-safe.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Authoritative saved application selections",
                    content = @Content(schema = @Schema(
                            implementation =
                                    ApplicationDocumentSelectionsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid explicit selection"),
            @ApiResponse(responseCode = "404", description = "Application or exact reference not found"),
            @ApiResponse(
                    responseCode = "409",
                    description = "Stale application version or conflicting idempotency-key reuse",
                    content = @Content(schema = @Schema(
                            implementation =
                                    ApplicationSelectionConflictResponse.class))),
            @ApiResponse(responseCode = "503", description = "Reference verifier unavailable")
    })
    public ResponseEntity<ApplicationDocumentSelectionsResponse> save(
            @PathVariable UUID applicationId,
            @Parameter(
                    description = "Replay-safe command key",
                    schema = @Schema(
                            maxLength = 128,
                            pattern = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SaveApplicationDocumentSelectionsRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(service.save(
                owner(authentication),
                applicationId,
                idempotencyKey,
                request));
    }

    private String owner(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            throw new IllegalStateException("Validated access token is required.");
        }
        return authentication.getName();
    }
}
