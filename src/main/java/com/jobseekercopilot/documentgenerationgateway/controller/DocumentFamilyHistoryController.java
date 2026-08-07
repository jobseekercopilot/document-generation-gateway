package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyCurrentResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyHistoryResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyPageResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.SelectFamilyCurrentRequest;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentFamilyHistoryService;
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

    public DocumentFamilyHistoryController(DocumentFamilyHistoryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Page the authenticated owner's document families")
    public ResponseEntity<DocumentFamilyPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return ResponseEntity.ok(service.list(
                owner(authentication), page, size));
    }

    @GetMapping("/{documentFamilyId}")
    @Operation(summary = "Get complete content-free document-family history")
    public ResponseEntity<DocumentFamilyHistoryResponse> history(
            @PathVariable UUID documentFamilyId,
            Authentication authentication) {
        return ResponseEntity.ok(service.history(
                owner(authentication), documentFamilyId));
    }

    @PatchMapping("/{documentFamilyId}/current")
    @Operation(summary = "Explicitly move a document-family current pointer")
    public ResponseEntity<DocumentFamilyCurrentResponse> selectCurrent(
            @PathVariable UUID documentFamilyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SelectFamilyCurrentRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(service.selectCurrent(
                owner(authentication), documentFamilyId, idempotencyKey, request));
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
