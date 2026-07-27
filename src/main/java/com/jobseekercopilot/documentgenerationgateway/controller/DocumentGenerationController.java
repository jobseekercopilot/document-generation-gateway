package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentGenerationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentFileDownloadService;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/document-generation")
@SecurityRequirement(name = "bearerAuth")
public class DocumentGenerationController {
    private final DocumentGenerationService service;
    private final DocumentFileDownloadService downloadService;

    public DocumentGenerationController(DocumentGenerationService service, DocumentFileDownloadService downloadService) {
        this.service = service;
        this.downloadService = downloadService;
    }

    @PostMapping("/jobs/{jobId}/generate")
    @Operation(summary = "Generate a tailored CV and cover letter and export DOCX/PDF files")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents generated, exported, and stored"),
            @ApiResponse(responseCode = "400", description = "Invalid or mismatched job data"),
            @ApiResponse(responseCode = "401", description = "No authenticated user"),
            @ApiResponse(responseCode = "502", description = "A downstream service failed")
    })
    public ResponseEntity<DocumentGenerationResponse> generate(
            @PathVariable String jobId,
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(hidden = true)
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody DocumentGenerationRequest request) {
        if (isBlank(request.getJob().getId())
                || isBlank(request.getJob().getTitle())
                || isBlank(request.getJob().getCompany())
                || isBlank(request.getJob().getDescription())
                || !jobId.equals(request.getJob().getId())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(
                service.generate(authenticatedSubject(authentication), authorization, request.getJob()));
    }

    @GetMapping("/files/{fileId}/download")
    @Operation(summary = "Download an exported generated document file")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Exported file bytes"),
            @ApiResponse(responseCode = "401", description = "No authenticated user"),
            @ApiResponse(responseCode = "404", description = "Exported file not found"),
            @ApiResponse(responseCode = "502", description = "Document store failed")
    })
    public ResponseEntity<byte[]> download(
            @PathVariable UUID fileId,
            @Parameter(hidden = true) Authentication authentication) {
        return downloadService.download(fileId, authenticatedSubject(authentication));
    }

    @PostMapping(value = "/documents/{generatedDocumentId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an edited CV or cover letter file")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Uploaded file stored and latest file metadata returned"),
            @ApiResponse(responseCode = "400", description = "Invalid upload request"),
            @ApiResponse(responseCode = "502", description = "Document export service failed")
    })
    public ResponseEntity<DocumentUploadResponse> uploadReplacement(
            @PathVariable UUID generatedDocumentId,
            @Parameter(hidden = true) Authentication authentication,
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentKind") DocumentKind documentKind,
            @RequestParam("uploadedFormat") UploadFormat uploadedFormat) {
        return ResponseEntity.ok(service.uploadReplacement(
                generatedDocumentId,
                authenticatedSubject(authentication),
                file,
                documentKind,
                uploadedFormat));
    }

    @PostMapping(value = "/applications/{applicationId}/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Replace the active CV or cover letter for an application")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Replacement DOCX stored, PDF generated, and application references updated"),
            @ApiResponse(responseCode = "202", description = "Replacement accepted and awaiting recoverable completion"),
            @ApiResponse(responseCode = "400", description = "Invalid upload or locked application"),
            @ApiResponse(responseCode = "401", description = "No authenticated user"),
            @ApiResponse(responseCode = "502", description = "Downstream service failed")
    })
    public ResponseEntity<DocumentUploadResponse> replaceApplicationDocument(
            @PathVariable UUID applicationId,
            @Parameter(hidden = true) Authentication authentication,
            @RequestParam("documentType") DocumentKind documentType,
            @RequestParam("file") MultipartFile file) {
        DocumentUploadResponse response = service.replaceApplicationDocument(
                applicationId,
                authenticatedSubject(authentication),
                file,
                documentType);
        return "COMPLETED".equals(response.operationStatus())
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    private String authenticatedSubject(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || isBlank(authentication.getName())) {
            throw new IllegalStateException("Validated access token is required.");
        }
        return authentication.getName();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
