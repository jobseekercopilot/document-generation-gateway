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
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
public class DocumentGenerationController {
    private static final String USER_ID_ATTRIBUTE = "USER_ID";
    private static final String USER_ID_HEADER = "X-User-Id";
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
            HttpServletRequest servletRequest,
            @PathVariable String jobId,
            @Parameter(in = ParameterIn.HEADER, name = USER_ID_HEADER, required = false)
            @RequestHeader(name = USER_ID_HEADER, required = false) String headerUserId,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Valid @RequestBody DocumentGenerationRequest request) {
        String userId = (String) servletRequest.getAttribute(USER_ID_ATTRIBUTE);
        if (userId == null || userId.isBlank()) userId = headerUserId;
        if (userId == null || userId.isBlank()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (isBlank(request.getJob().getId())
                || isBlank(request.getJob().getTitle())
                || isBlank(request.getJob().getCompany())
                || isBlank(request.getJob().getDescription())
                || !jobId.equals(request.getJob().getId())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.generate(userId, authorization, request.getJob()));
    }

    @GetMapping("/files/{fileId}/download")
    @Operation(summary = "Download an exported generated document file")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Exported file bytes"),
            @ApiResponse(responseCode = "401", description = "No authenticated user"),
            @ApiResponse(responseCode = "404", description = "Exported file not found"),
            @ApiResponse(responseCode = "502", description = "Document store failed")
    })
    public ResponseEntity<byte[]> download(@PathVariable UUID fileId) {
        return downloadService.download(fileId);
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
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentKind") DocumentKind documentKind,
            @RequestParam("uploadedFormat") UploadFormat uploadedFormat) {
        return ResponseEntity.ok(service.uploadReplacement(generatedDocumentId, file, documentKind, uploadedFormat));
    }

    @PostMapping(value = "/applications/{applicationId}/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Replace the active CV or cover letter for an application")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Replacement DOCX stored, PDF generated, and application references updated"),
            @ApiResponse(responseCode = "400", description = "Invalid upload or locked application"),
            @ApiResponse(responseCode = "401", description = "No authenticated user"),
            @ApiResponse(responseCode = "502", description = "Downstream service failed")
    })
    public ResponseEntity<DocumentUploadResponse> replaceApplicationDocument(
            HttpServletRequest servletRequest,
            @PathVariable UUID applicationId,
            @Parameter(in = ParameterIn.HEADER, name = USER_ID_HEADER, required = false)
            @RequestHeader(name = USER_ID_HEADER, required = false) String headerUserId,
            @RequestParam("documentType") DocumentKind documentType,
            @RequestParam("file") MultipartFile file) {
        String userId = (String) servletRequest.getAttribute(USER_ID_ATTRIBUTE);
        if (userId == null || userId.isBlank()) userId = headerUserId;
        return ResponseEntity.ok(service.replaceApplicationDocument(applicationId, userId, file, documentType));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
