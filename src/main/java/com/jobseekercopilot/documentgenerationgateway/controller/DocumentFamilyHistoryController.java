package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyCurrentResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyHistoryResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyPageResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.SelectFamilyCurrentRequest;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentFamilyHistoryService;
import com.jobseekercopilot.documentgenerationgateway.service.OwnerDocumentRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-generation/document-families")
@SecurityRequirement(name = "bearerAuth")
public class DocumentFamilyHistoryController {
    private final DocumentFamilyHistoryService service;
    private final OwnerDocumentRateLimiter documentRateLimiter;

    public DocumentFamilyHistoryController(
            DocumentFamilyHistoryService service,
            OwnerDocumentRateLimiter documentRateLimiter) {
        this.service = service;
        this.documentRateLimiter = documentRateLimiter;
    }

    @GetMapping
    @Operation(summary = "Page the authenticated owner's document families")
    public ResponseEntity<DocumentFamilyPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        String ownerId = owner(authentication);
        documentRateLimiter.metadata(ownerId);
        return ResponseEntity.ok(service.list(ownerId, page, size));
    }

    @GetMapping("/{documentFamilyId}")
    @Operation(summary = "Get complete content-free document-family history")
    public ResponseEntity<DocumentFamilyHistoryResponse> history(
            @PathVariable UUID documentFamilyId,
            Authentication authentication) {
        String ownerId = owner(authentication);
        documentRateLimiter.metadata(ownerId);
        return ResponseEntity.ok(service.history(ownerId, documentFamilyId));
    }

    @PatchMapping("/{documentFamilyId}/current")
    @Operation(summary = "Explicitly move a document-family current pointer")
    public ResponseEntity<DocumentFamilyCurrentResponse> selectCurrent(
            @PathVariable UUID documentFamilyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SelectFamilyCurrentRequest request,
            Authentication authentication) {
        String ownerId = owner(authentication);
        documentRateLimiter.metadata(ownerId);
        return ResponseEntity.ok(service.selectCurrent(
                ownerId, documentFamilyId, idempotencyKey, request));
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
