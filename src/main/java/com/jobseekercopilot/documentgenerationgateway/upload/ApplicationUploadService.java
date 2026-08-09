package com.jobseekercopilot.documentgenerationgateway.upload;

import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentUploadOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationUploadConflictException;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationUploadNotFoundException;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationUploadTooLargeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ApplicationUploadService {
    static final long MAXIMUM_FILE_BYTES = 10L * 1024 * 1024;
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final ApplicationUploadOperationRepository repository;
    private final ApplicationUploadDownstreamClient downstream;

    public ApplicationUploadService(
            ApplicationUploadOperationRepository repository,
            ApplicationUploadDownstreamClient downstream) {
        this.repository = repository;
        this.downstream = downstream;
    }

    public ApplicationDocumentUploadOperationResponse upload(
            String ownerId,
            UUID applicationId,
            String jobId,
            DocumentKind documentType,
            UploadFormat fileType,
            String idempotencyKey,
            MultipartFile file) {
        requireRequest(
                ownerId,
                applicationId,
                jobId,
                documentType,
                fileType,
                idempotencyKey,
                file);
        byte[] bytes = bytes(file);
        if (bytes.length > MAXIMUM_FILE_BYTES) {
            throw new ApplicationUploadTooLargeException();
        }
        String payloadSha256 = sha256(bytes);
        String fingerprint = fingerprint(
                applicationId, jobId, documentType, fileType, payloadSha256);
        ApplicationUploadOperation operation = repository.createOrReplay(
                ownerId,
                idempotencyKey,
                applicationId,
                jobId.trim(),
                documentType,
                fileType,
                fingerprint,
                payloadSha256);
        if (operation.state() == ApplicationUploadState.COMPLETED
                || operation.state() == ApplicationUploadState.REJECTED) {
            return response(operation);
        }
        if (operation.documentId() == null) {
            operation = store(operation, bytes, file.getOriginalFilename());
        }
        if (operation.state() == ApplicationUploadState.STORE_READY
                || operation.state() == ApplicationUploadState.LINKING
                || (operation.state() == ApplicationUploadState.RECOVERY_REQUIRED
                        && operation.documentId() != null)) {
            operation = link(operation);
        }
        return response(operation);
    }

    public ApplicationDocumentUploadOperationResponse get(
            String ownerId, UUID operationId) {
        requireOwner(ownerId);
        return repository.findByOwnerAndId(operationId, ownerId)
                .map(this::response)
                .orElseThrow(ApplicationUploadNotFoundException::new);
    }

    private ApplicationUploadOperation store(
            ApplicationUploadOperation operation,
            byte[] bytes,
            String filename) {
        try {
            Map<String, Object> application = requireApplication(operation);
            requireApplicationMutable(application);
        } catch (ApplicationUploadNotFoundException
                | ApplicationUploadConflictException exception) {
            throw exception;
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ApplicationUploadNotFoundException();
            }
            if (exception.getStatusCode().is4xxClientError()) {
                throw new ApplicationUploadConflictException(
                        "The saved application cannot accept this upload.");
            }
            return recoverable(operation, "APPLICATION_TRACKER_UNAVAILABLE");
        } catch (RestClientException | IllegalStateException exception) {
            return recoverable(operation, "APPLICATION_TRACKER_UNAVAILABLE");
        }
        try {
            Map<String, Object> stored = downstream.upload(operation, bytes, filename);
            String storeState = text(stored, "state");
            if (terminalStoreFailure(storeState)) {
                return repository.markFailure(
                        operation,
                        ApplicationUploadState.REJECTED,
                        storeState,
                        optionalText(stored, "failureCode", "UPLOAD_REJECTED"),
                        "The uploaded document did not pass secure processing.");
            }
            if (!"READY".equals(storeState)) {
                return repository.markFailure(
                        operation,
                        ApplicationUploadState.RECOVERY_REQUIRED,
                        storeState,
                        "UPLOAD_PROCESSING_INCOMPLETE",
                        "Secure document processing has not completed.");
            }
            try {
                validateStoreResponse(operation, stored, bytes.length);
            } catch (IllegalStateException evidenceMismatch) {
                return repository.markFailure(
                        operation,
                        ApplicationUploadState.RECOVERY_REQUIRED,
                        storeState,
                        "DOCUMENT_STORE_EVIDENCE_MISMATCH",
                        "Document Store returned evidence that did not match this upload.");
            }
            return repository.markStoreReady(
                    operation,
                    requiredUuid(stored, "operationId"),
                    storeState,
                    requiredUuid(stored, "documentId"));
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                String code = exception.getStatusCode() == HttpStatus.PAYLOAD_TOO_LARGE
                        ? "UPLOAD_TOO_LARGE"
                        : exception.getStatusCode() == HttpStatus.CONFLICT
                                ? "UPLOAD_CONFLICT"
                                : "UPLOAD_REJECTED";
                return repository.markFailure(
                        operation,
                        ApplicationUploadState.REJECTED,
                        null,
                        code,
                        "The uploaded document was rejected.");
            }
            return recoverable(operation, "DOCUMENT_STORE_UNAVAILABLE");
        } catch (RestClientException | IllegalStateException exception) {
            return recoverable(operation, "DOCUMENT_STORE_UNAVAILABLE");
        }
    }

    private ApplicationUploadOperation link(ApplicationUploadOperation operation) {
        try {
            Map<String, Object> application = requireApplication(operation);
            requireApplicationMutable(application);
            UUID currentTarget = selectedDocument(application, operation.documentType());
            if (operation.documentId().equals(currentTarget)) {
                return repository.markCompleted(operation, requiredVersion(application));
            }
            UUID cvDocumentId = operation.documentType() == DocumentKind.CV
                    ? operation.documentId()
                    : uuid(application.get("cvDocumentId"));
            UUID coverLetterDocumentId = operation.documentType() == DocumentKind.COVER_LETTER
                    ? operation.documentId()
                    : uuid(application.get("coverLetterDocumentId"));
            operation = repository.markLinking(operation);
            Map<String, Object> linked = downstream.saveSelections(
                    operation,
                    requiredVersion(application),
                    cvDocumentId,
                    coverLetterDocumentId);
            try {
                requireLinked(operation, linked);
            } catch (IllegalStateException evidenceMismatch) {
                return repository.markFailure(
                        operation,
                        ApplicationUploadState.RECOVERY_REQUIRED,
                        operation.storeState(),
                        "APPLICATION_TRACKER_EVIDENCE_MISMATCH",
                        "Application Tracker returned evidence that did not match this link.");
            }
            return repository.markCompleted(operation, requiredVersion(linked));
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                try {
                    Map<String, Object> current = downstream.application(
                            operation.ownerId(), operation.applicationId());
                    if (current != null
                            && operation.documentId().equals(
                                    selectedDocument(current, operation.documentType()))) {
                        return repository.markCompleted(operation, requiredVersion(current));
                    }
                } catch (RestClientException | IllegalStateException ignored) {
                    return recoverable(operation, "APPLICATION_TRACKER_UNAVAILABLE");
                }
                return repository.markFailure(
                        operation,
                        ApplicationUploadState.RECOVERY_REQUIRED,
                        operation.storeState(),
                        "APPLICATION_CHANGED",
                        "The application changed while its document was being linked.");
            }
            if (exception.getStatusCode().is4xxClientError()) {
                return repository.markFailure(
                        operation,
                        ApplicationUploadState.REJECTED,
                        operation.storeState(),
                        "APPLICATION_LINK_REJECTED",
                        "The clean document could not be linked to this application.");
            }
            return recoverable(operation, "APPLICATION_TRACKER_UNAVAILABLE");
        } catch (ApplicationUploadNotFoundException
                | ApplicationUploadConflictException exception) {
            return repository.markFailure(
                    operation,
                    ApplicationUploadState.REJECTED,
                    operation.storeState(),
                    "APPLICATION_LINK_REJECTED",
                    "The clean document could not be linked to this application.");
        } catch (RestClientException | IllegalStateException exception) {
            return recoverable(operation, "APPLICATION_TRACKER_UNAVAILABLE");
        }
    }

    private ApplicationUploadOperation recoverable(
            ApplicationUploadOperation operation, String code) {
        return repository.markFailure(
                operation,
                ApplicationUploadState.RECOVERY_REQUIRED,
                operation.storeState(),
                code,
                "The operation can be retried safely with the same key and file.");
    }

    private Map<String, Object> requireApplication(ApplicationUploadOperation operation) {
        Map<String, Object> application = downstream.application(
                operation.ownerId(), operation.applicationId());
        if (application == null) {
            throw new ApplicationUploadNotFoundException();
        }
        String canonicalJobId = optionalText(
                application,
                "canonicalJobId",
                optionalText(application, "jobId", null));
        if (!operation.jobId().equals(canonicalJobId)) {
            throw new ApplicationUploadNotFoundException();
        }
        return application;
    }

    private void requireApplicationMutable(Map<String, Object> application) {
        String status = text(application, "status");
        if (!"SAVED".equals(status) && !"DOCUMENTS_GENERATED".equals(status)) {
            throw new ApplicationUploadConflictException(
                    "Documents can be linked only while the application is saved.");
        }
    }

    private void validateStoreResponse(
            ApplicationUploadOperation operation,
            Map<String, Object> stored,
            long originalSize) {
        if (!operation.applicationId().toString().equals(text(stored, "applicationId"))
                || !operation.jobId().equals(text(stored, "jobId"))
                || !operation.documentType().name().equals(text(stored, "documentType"))
                || !operation.fileType().name().equals(text(stored, "fileType"))
                || !operation.payloadSha256().equals(text(stored, "originalSha256"))
                || originalSize != requiredLong(stored, "originalSize")) {
            throw new IllegalStateException(
                    "Document Store returned mismatched upload evidence.");
        }
        requiredUuid(stored, "operationId");
        requiredUuid(stored, "documentId");
    }

    private void requireLinked(
            ApplicationUploadOperation operation, Map<String, Object> linked) {
        if (!operation.applicationId().equals(uuid(linked.get("id")))
                || !operation.documentId().equals(
                        selectedDocument(linked, operation.documentType()))) {
            throw new IllegalStateException(
                    "Application Tracker returned mismatched selection evidence.");
        }
    }

    private UUID selectedDocument(Map<String, Object> application, DocumentKind type) {
        return uuid(application.get(type == DocumentKind.CV
                ? "cvDocumentId"
                : "coverLetterDocumentId"));
    }

    private boolean terminalStoreFailure(String state) {
        return "REJECTED".equals(state)
                || "FAILED".equals(state)
                || "SCAN_UNAVAILABLE".equals(state);
    }

    private ApplicationDocumentUploadOperationResponse response(
            ApplicationUploadOperation operation) {
        return new ApplicationDocumentUploadOperationResponse(
                operation.id(),
                operation.applicationId(),
                operation.jobId(),
                operation.documentType(),
                operation.fileType(),
                operation.state(),
                operation.storeState(),
                operation.documentId(),
                operation.applicationVersion(),
                operation.failureCode(),
                operation.failureMessage(),
                operation.createdAt(),
                operation.updatedAt());
    }

    private void requireRequest(
            String ownerId,
            UUID applicationId,
            String jobId,
            DocumentKind documentType,
            UploadFormat fileType,
            String idempotencyKey,
            MultipartFile file) {
        requireOwner(ownerId);
        if (applicationId == null
                || jobId == null
                || jobId.isBlank()
                || jobId.length() > 255
                || documentType == null
                || fileType == null) {
            throw new IllegalArgumentException(
                    "Application, canonical job, document type and file type are required.");
        }
        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1-128 safe characters.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty PDF or DOCX is required.");
        }
        if (file.getSize() > MAXIMUM_FILE_BYTES) {
            throw new ApplicationUploadTooLargeException();
        }
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalStateException("Validated access token is required.");
        }
    }

    private byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException(
                    "Uploaded file could not be read.", exception);
        }
    }

    private String fingerprint(
            UUID applicationId,
            String jobId,
            DocumentKind documentType,
            UploadFormat fileType,
            String payloadSha256) {
        return sha256(String.join(
                        "\n",
                        applicationId.toString(),
                        jobId.trim(),
                        documentType.name(),
                        fileType.name(),
                        payloadSha256)
                .getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private String text(Map<String, Object> source, String key) {
        String value = optionalText(source, key, null);
        if (value == null) {
            throw new IllegalStateException("A downstream response omitted " + key + ".");
        }
        return value;
    }

    private String optionalText(
            Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        return value == null ? fallback : Objects.toString(value);
    }

    private UUID requiredUuid(Map<String, Object> source, String key) {
        UUID value = uuid(source.get(key));
        if (value == null) {
            throw new IllegalStateException("A downstream response omitted " + key + ".");
        }
        return value;
    }

    private UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String string) {
            try {
                return UUID.fromString(string);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private long requiredVersion(Map<String, Object> application) {
        Object value = application.get("version");
        if (value instanceof Number number && number.longValue() >= 0) {
            return number.longValue();
        }
        throw new IllegalStateException(
                "Application Tracker returned no application version.");
    }

    private long requiredLong(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Number number && number.longValue() >= 0) {
            return number.longValue();
        }
        throw new IllegalStateException("A downstream response omitted " + key + ".");
    }
}
