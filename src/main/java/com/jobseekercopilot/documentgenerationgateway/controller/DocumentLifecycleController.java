package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentApplicationAssociationsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentVersionLifecycleResponse;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-generation/document-versions")
@SecurityRequirement(name = "bearerAuth")
public class DocumentLifecycleController {
    private final DocumentLifecycleService service;

    public DocumentLifecycleController(DocumentLifecycleService service) {
        this.service = service;
    }

    @GetMapping("/{documentId}/application-associations")
    @Operation(
            summary = "Get exact-version application associations",
            description = "Returns content-free owner-scoped draft selections and frozen uses so lifecycle UI can warn without retaining or exposing document content.")
    public ResponseEntity<DocumentApplicationAssociationsResponse> associations(
            @PathVariable UUID documentId,
            Authentication authentication) {
        return ResponseEntity.ok(service.associations(
                owner(authentication), documentId));
    }

    @PatchMapping("/{documentId}/archive")
    @Operation(
            summary = "Archive one exact document version",
            description = "Clears current for this version, preserves retained downloads, and never redirects an application reference.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Version archived or replayed"),
            @ApiResponse(responseCode = "404", description = "Version is absent or owned by another user"),
            @ApiResponse(responseCode = "409", description = "Lifecycle coordination failed safely")
    })
    public ResponseEntity<DocumentVersionLifecycleResponse> archive(
            @PathVariable UUID documentId,
            Authentication authentication) {
        return ResponseEntity.ok(service.archive(
                owner(authentication), documentId));
    }

    @PatchMapping("/{documentId}/restore")
    @Operation(
            summary = "Restore one archived or recoverably deleted version",
            description = "Restores availability within policy without making the version current or changing application references.")
    public ResponseEntity<DocumentVersionLifecycleResponse> restore(
            @PathVariable UUID documentId,
            Authentication authentication) {
        return ResponseEntity.ok(service.restore(
                owner(authentication), documentId));
    }

    @DeleteMapping("/{documentId}")
    @Operation(
            summary = "Recoverably delete one exact document version",
            description = "Makes bytes unavailable for the approved recovery window; archive should be offered first when applications are associated.")
    @ApiResponse(
            responseCode = "204",
            description = "Version moved to recoverable deletion without returning document content")
    public ResponseEntity<Void> delete(
            @PathVariable UUID documentId,
            Authentication authentication) {
        service.delete(owner(authentication), documentId);
        return ResponseEntity.noContent().build();
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
