package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.ApproveGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.dto.GenerationOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.StartGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.generation.DurableGenerationService;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentFileDownloadService;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentGenerationService;
import com.jobseekercopilot.documentgenerationgateway.service.OwnerDocumentRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final DurableGenerationService durableGenerationService;
    private final OwnerDocumentRateLimiter documentRateLimiter;

    public DocumentGenerationController(
            DocumentGenerationService service,
            DocumentFileDownloadService downloadService,
            DurableGenerationService durableGenerationService,
            OwnerDocumentRateLimiter documentRateLimiter) {
        this.service = service;
        this.downloadService = downloadService;
        this.durableGenerationService = durableGenerationService;
        this.documentRateLimiter = documentRateLimiter;
    }

    @PostMapping("/saved-jobs/{savedJobId}/operations")
    @Operation(
            summary = "Start or replay durable draft generation from an owner-scoped saved job",
            description = "The same owner and Idempotency-Key return the same operation. "
                    + "The request accepts only an active canonical Job 2.0 snapshot, freezes "
                    + "Job/Profile evidence and one evidence selection for each explicitly "
                    + "requested CV or cover-letter output, creates or resolves the saved "
                    + "application before paid work, "
                    + "applies one deadline across downstream calls, "
                    + "reserves AI Credit per selected output, performs at most one automatic "
                    + "model invocation per output and stores independently usable DRAFT documents. "
                    + "Legacy requests without outputs retain the paired workflow during migration.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Operation accepted or replayed"),
            @ApiResponse(responseCode = "400", description = "Invalid idempotency key"),
            @ApiResponse(responseCode = "401", description = "No authenticated user"),
            @ApiResponse(responseCode = "409", description = "Idempotency or saved-job conflict")
    })
    public ResponseEntity<GenerationOperationResponse> startOperation(
            @PathVariable UUID savedJobId,
            @Parameter(hidden = true) Authentication authentication,
            @Parameter(hidden = true)
            @RequestHeader(name = "Authorization", required = false)
            String authorization,
            @RequestHeader(name = "Idempotency-Key")
            String idempotencyKey,
            @Valid @RequestBody StartGenerationRequest request) {
        return ResponseEntity.accepted().body(
                durableGenerationService.start(
                        authenticatedSubject(authentication),
                        authorization,
                        savedJobId,
                        idempotencyKey,
                        request));
    }

    @GetMapping("/operations/{operationId}")
    @Operation(summary = "Get an owner-scoped durable generation operation")
    public ResponseEntity<GenerationOperationResponse> getOperation(
            @PathVariable UUID operationId,
            @Parameter(hidden = true) Authentication authentication) {
        return ResponseEntity.ok(durableGenerationService.get(
                authenticatedSubject(authentication), operationId));
    }

    @PostMapping("/operations/{operationId}/approve")
    @Operation(
            summary = "Approve exact drafts and complete export and Tracker linkage",
            description = "Approval must name exactly the DRAFT document IDs returned by this "
                    + "operation; an unrequested or failed output remains absent.")
    public ResponseEntity<GenerationOperationResponse> approveOperation(
            @PathVariable UUID operationId,
            @Parameter(hidden = true) Authentication authentication,
            @Valid @RequestBody ApproveGenerationRequest request) {
        GenerationOperationResponse response =
                durableGenerationService.approve(
                        authenticatedSubject(authentication),
                        operationId,
                        request);
        return response.manualActionRequired()
                ? ResponseEntity.accepted().body(response)
                : ResponseEntity.ok(response);
    }

    @DeleteMapping("/operations/{operationId}")
    @Operation(
            summary = "Cancel a generation operation while cancellation remains deterministic")
    public ResponseEntity<GenerationOperationResponse> cancelOperation(
            @PathVariable UUID operationId,
            @Parameter(hidden = true) Authentication authentication) {
        return ResponseEntity.ok(durableGenerationService.cancel(
                authenticatedSubject(authentication), operationId));
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
        String ownerId = authenticatedSubject(authentication);
        documentRateLimiter.download(ownerId);
        return downloadService.download(fileId, ownerId);
    }

    @GetMapping("/documents/{documentId}/artifacts/{artifactId}/download")
    @Operation(
            summary = "Download one exact retained document artifact",
            description = "Authorises the exact document-version/artifact relationship and never changes current, lifecycle, artifact activity or application selections.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Exact retained artifact bytes with private no-store download policy",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary")),
                    headers = {
                            @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            @Header(name = "Content-Length", schema = @Schema(type = "integer", format = "int64")),
                            @Header(name = "X-Content-Type-Options", schema = @Schema(type = "string")),
                            @Header(name = "Cache-Control", schema = @Schema(type = "string")),
                            @Header(name = "Pragma", schema = @Schema(type = "string"))
                    }),
            @ApiResponse(responseCode = "401", description = "No authenticated user"),
            @ApiResponse(responseCode = "404", description = "Artifact relationship not found for this owner"),
            @ApiResponse(responseCode = "409", description = "Artifact is not safely downloadable"),
            @ApiResponse(responseCode = "502", description = "Document Store failed")
    })
    public ResponseEntity<byte[]> downloadExactArtifact(
            @PathVariable UUID documentId,
            @PathVariable UUID artifactId,
            @Parameter(hidden = true) Authentication authentication) {
        String ownerId = authenticatedSubject(authentication);
        documentRateLimiter.download(ownerId);
        return downloadService.downloadExactArtifact(
                documentId, artifactId, ownerId);
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
