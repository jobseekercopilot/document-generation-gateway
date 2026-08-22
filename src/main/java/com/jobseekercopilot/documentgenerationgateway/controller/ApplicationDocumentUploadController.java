package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentUploadOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.upload.ApplicationUploadService;
import com.jobseekercopilot.documentgenerationgateway.upload.ApplicationUploadState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class ApplicationDocumentUploadController {
    private final ApplicationUploadService service;

    public ApplicationDocumentUploadController(ApplicationUploadService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/api/v1/document-generation/applications/{applicationId}/document-uploads",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Upload and link one exact application document for free",
            description = "The saved application is verified before the file is sent to Document Store. Only a clean approved immutable version is passed to Application Tracker for independent exact-reference verification. This path never calls Payment, CV/Letter Service, Document Export or an LLM.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Operation completed, replayed or reached a stable terminal rejection",
                    content = @Content(schema = @Schema(
                            implementation = ApplicationDocumentUploadOperationResponse.class))),
            @ApiResponse(
                    responseCode = "202",
                    description = "Operation retained for safe retry or linking recovery"),
            @ApiResponse(responseCode = "400", description = "Invalid upload context"),
            @ApiResponse(responseCode = "404", description = "Application not found for this owner"),
            @ApiResponse(
                    responseCode = "409",
                    description = "Idempotency conflict or immutable application state"),
            @ApiResponse(responseCode = "413", description = "File exceeds 10 MiB")
    })
    public ResponseEntity<ApplicationDocumentUploadOperationResponse> upload(
            @PathVariable UUID applicationId,
            @RequestParam String jobId,
            @RequestParam DocumentKind documentType,
            @RequestParam UploadFormat fileType,
            @Parameter(
                    description = "Replay-safe key bound to application, job, type and exact bytes",
                    schema = @Schema(
                            minLength = 1,
                            maxLength = 128,
                            pattern = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        ApplicationDocumentUploadOperationResponse response = service.upload(
                owner(authentication),
                applicationId,
                jobId,
                documentType,
                fileType,
                idempotencyKey,
                file);
        boolean accepted = response.state() != ApplicationUploadState.COMPLETED
                && response.state() != ApplicationUploadState.REJECTED;
        return ResponseEntity.status(accepted ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(response);
    }

    @GetMapping(
            value = "/api/v1/document-generation/application-document-uploads/{operationId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get one owner-scoped upload/link operation")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Operation found"),
            @ApiResponse(responseCode = "404", description = "Operation not found")
    })
    public ResponseEntity<ApplicationDocumentUploadOperationResponse> get(
            @PathVariable UUID operationId,
            Authentication authentication) {
        return ResponseEntity.ok(service.get(owner(authentication), operationId));
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
