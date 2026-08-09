package com.jobseekercopilot.documentgenerationgateway.exception;

import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationSelectionConflictResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.HttpHeaders;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApplicationSelectionDownstreamException.class)
    ResponseEntity<?> applicationSelectionFailure(
            ApplicationSelectionDownstreamException exception) {
        int status = exception.status().value();
        if (status == HttpStatus.CONFLICT.value()) {
            return ResponseEntity.status(exception.status()).body(
                    new ApplicationSelectionConflictResponse(
                            status,
                            exception.currentApplication() == null
                                    ? "Selection command conflicts with an earlier request."
                                    : "Application changed; refresh and retry.",
                            exception.currentApplication()));
        }
        String error = status == HttpStatus.NOT_FOUND.value()
                ? "APPLICATION_NOT_FOUND"
                : status == HttpStatus.BAD_REQUEST.value()
                        ? "INVALID_DOCUMENT_SELECTION"
                        : "APPLICATION_SELECTION_UNAVAILABLE";
        String message = status == HttpStatus.NOT_FOUND.value()
                ? "Application or document selection was not found."
                : status == HttpStatus.BAD_REQUEST.value()
                        ? "Document selection is invalid."
                        : "Document selections could not be saved.";
        HttpStatus responseStatus = status == HttpStatus.NOT_FOUND.value()
                        || status == HttpStatus.BAD_REQUEST.value()
                ? HttpStatus.valueOf(status)
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(responseStatus)
                .body(Map.of("error", error, "message", message));
    }

    @ExceptionHandler(OwnerDocumentRateLimitExceededException.class)
    ResponseEntity<Map<String, String>> documentRateLimited(
            OwnerDocumentRateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(
                        HttpHeaders.RETRY_AFTER,
                        Long.toString(exception.retryAfterSeconds()))
                .body(Map.of(
                        "error",
                        "DOCUMENT_RATE_LIMIT_EXCEEDED",
                        "message",
                        "Too many document requests; retry shortly."));
    }

    @ExceptionHandler(GenerationNotFoundException.class)
    ResponseEntity<Map<String, String>> operationNotFound(
            GenerationNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "GENERATION_OPERATION_NOT_FOUND",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(GenerationConflictException.class)
    ResponseEntity<Map<String, String>> operationConflict(
            GenerationConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "GENERATION_OPERATION_CONFLICT",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(ApplicationUploadConflictException.class)
    ResponseEntity<Map<String, String>> applicationUploadConflict(
            ApplicationUploadConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "APPLICATION_UPLOAD_CONFLICT",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(ApplicationUploadNotFoundException.class)
    ResponseEntity<Map<String, String>> applicationUploadNotFound(
            ApplicationUploadNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "APPLICATION_UPLOAD_NOT_FOUND",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(ApplicationUploadTooLargeException.class)
    ResponseEntity<Map<String, String>> applicationUploadTooLarge(
            ApplicationUploadTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", "APPLICATION_UPLOAD_TOO_LARGE",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidRequest(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", "Job is required"));
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, String>> downstreamFailure(RestClientException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error",
                        "DOWNSTREAM_FAILURE",
                        "message",
                        "A required service could not complete the request."));
    }

    @ExceptionHandler(DocumentHistoryDownstreamException.class)
    ResponseEntity<Map<String, String>> documentHistoryFailure(
            DocumentHistoryDownstreamException exception) {
        int status = exception.status().value();
        String error = status == HttpStatus.NOT_FOUND.value()
                ? "DOCUMENT_FAMILY_NOT_FOUND"
                : status == HttpStatus.CONFLICT.value()
                        ? "DOCUMENT_CURRENT_CONFLICT"
                        : "DOCUMENT_HISTORY_REQUEST_FAILED";
        String message = status == HttpStatus.NOT_FOUND.value()
                ? "Document family was not found."
                : status == HttpStatus.CONFLICT.value()
                        ? "Document family changed; refresh and retry."
                        : "Document history request could not be completed.";
        return ResponseEntity.status(exception.status())
                .body(Map.of("error", error, "message", message));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> emptyDownstreamResponse(IllegalStateException exception) {
        if ("DOCUMENT_CONVERSION_FAILED".equals(exception.getMessage())) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error", "DOCUMENT_CONVERSION_FAILED",
                            "message", "The uploaded document could not be converted to PDF."));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of(
                        "error",
                        "DOWNSTREAM_FAILURE",
                        "message",
                        "A required service returned an invalid response."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidUpload(IllegalArgumentException exception) {
        if ("Only .docx files can be uploaded.".equals(exception.getMessage())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_DOCUMENT_TYPE",
                    "message", "Only .docx files can be uploaded."));
        }
        if ("Documents cannot be replaced after the application has been marked as applied.".equals(exception.getMessage())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "DOCUMENT_LOCKED",
                    "message", "Documents cannot be replaced after the application has been marked as applied."));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "UPLOAD_TOO_LARGE", "message", "Uploaded file must be 25MB or less."));
    }
}
