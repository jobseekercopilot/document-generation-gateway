package com.jobseekercopilot.documentgenerationgateway.upload;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationUploadConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApplicationUploadOperationRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, owner_id, idempotency_key, application_id, job_id,
                   document_type, file_type, request_fingerprint,
                   payload_sha256, state, store_operation_id, store_state,
                   document_id, application_version, failure_code,
                   failure_message, version, created_at, updated_at
              FROM application_document_upload_operations
            """;

    private final JdbcTemplate jdbc;

    public ApplicationUploadOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApplicationUploadOperation createOrReplay(
            String ownerId,
            String idempotencyKey,
            UUID applicationId,
            String jobId,
            DocumentKind documentType,
            UploadFormat fileType,
            String requestFingerprint,
            String payloadSha256) {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO application_document_upload_operations (
                        id, owner_id, idempotency_key, application_id, job_id,
                        document_type, file_type, request_fingerprint,
                        payload_sha256, state, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    id,
                    ownerId,
                    idempotencyKey,
                    applicationId,
                    jobId,
                    documentType.name(),
                    fileType.name(),
                    requestFingerprint,
                    payloadSha256,
                    ApplicationUploadState.RECEIVED.name(),
                    atOffset(now),
                    atOffset(now));
            return findByOwnerAndId(id, ownerId).orElseThrow();
        } catch (DataIntegrityViolationException duplicate) {
            ApplicationUploadOperation replay = findByOwnerAndKey(
                    ownerId, idempotencyKey).orElseThrow(() -> duplicate);
            if (!replay.applicationId().equals(applicationId)
                    || !replay.jobId().equals(jobId)
                    || replay.documentType() != documentType
                    || replay.fileType() != fileType
                    || !replay.requestFingerprint().equals(requestFingerprint)
                    || !replay.payloadSha256().equals(payloadSha256)) {
                throw new ApplicationUploadConflictException(
                        "Idempotency-Key was already used for a different application upload.");
            }
            return replay;
        }
    }

    public Optional<ApplicationUploadOperation> findByOwnerAndId(
            UUID id, String ownerId) {
        return one(SELECT_COLUMNS + " WHERE id = ? AND owner_id = ?", id, ownerId);
    }

    Optional<ApplicationUploadOperation> findByOwnerAndKey(
            String ownerId, String idempotencyKey) {
        return one(
                SELECT_COLUMNS + " WHERE owner_id = ? AND idempotency_key = ?",
                ownerId,
                idempotencyKey);
    }

    public ApplicationUploadOperation markStoreReady(
            ApplicationUploadOperation operation,
            UUID storeOperationId,
            String storeState,
            UUID documentId) {
        jdbc.update("""
                UPDATE application_document_upload_operations
                   SET state = ?, store_operation_id = ?, store_state = ?,
                       document_id = ?, failure_code = NULL,
                       failure_message = NULL, version = version + 1,
                       updated_at = ?
                 WHERE id = ? AND owner_id = ? AND state <> 'COMPLETED'
                   AND (document_id IS NULL OR document_id = ?)
                """,
                ApplicationUploadState.STORE_READY.name(),
                storeOperationId,
                storeState,
                documentId,
                atOffset(Instant.now()),
                operation.id(),
                operation.ownerId(),
                documentId);
        ApplicationUploadOperation updated = reload(operation);
        if (updated.documentId() != null && !updated.documentId().equals(documentId)) {
            throw new ApplicationUploadConflictException(
                    "Document Store replay returned a different immutable version.");
        }
        return updated;
    }

    public ApplicationUploadOperation markLinking(ApplicationUploadOperation operation) {
        jdbc.update("""
                UPDATE application_document_upload_operations
                   SET state = ?, failure_code = NULL, failure_message = NULL,
                       version = version + 1, updated_at = ?
                 WHERE id = ? AND owner_id = ? AND state <> 'COMPLETED'
                """,
                ApplicationUploadState.LINKING.name(),
                atOffset(Instant.now()),
                operation.id(),
                operation.ownerId());
        return reload(operation);
    }

    public ApplicationUploadOperation markCompleted(
            ApplicationUploadOperation operation, long applicationVersion) {
        jdbc.update("""
                UPDATE application_document_upload_operations
                   SET state = ?, application_version = ?, failure_code = NULL,
                       failure_message = NULL, version = version + 1,
                       updated_at = ?
                 WHERE id = ? AND owner_id = ?
                """,
                ApplicationUploadState.COMPLETED.name(),
                applicationVersion,
                atOffset(Instant.now()),
                operation.id(),
                operation.ownerId());
        return reload(operation);
    }

    public ApplicationUploadOperation markFailure(
            ApplicationUploadOperation operation,
            ApplicationUploadState state,
            String storeState,
            String failureCode,
            String failureMessage) {
        if (state != ApplicationUploadState.REJECTED
                && state != ApplicationUploadState.RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("Upload failure state is invalid.");
        }
        jdbc.update("""
                UPDATE application_document_upload_operations
                   SET state = ?, store_state = COALESCE(?, store_state),
                       failure_code = ?, failure_message = ?,
                       version = version + 1, updated_at = ?
                 WHERE id = ? AND owner_id = ? AND state <> 'COMPLETED'
                """,
                state.name(),
                storeState,
                failureCode,
                bounded(failureMessage),
                atOffset(Instant.now()),
                operation.id(),
                operation.ownerId());
        return reload(operation);
    }

    private ApplicationUploadOperation reload(ApplicationUploadOperation operation) {
        return findByOwnerAndId(operation.id(), operation.ownerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Application upload operation disappeared during update."));
    }

    private Optional<ApplicationUploadOperation> one(String sql, Object... parameters) {
        return jdbc.query(sql, this::map, parameters).stream().findFirst();
    }

    private ApplicationUploadOperation map(ResultSet result, int row) throws SQLException {
        Number applicationVersion = (Number) result.getObject("application_version");
        return new ApplicationUploadOperation(
                result.getObject("id", UUID.class),
                result.getString("owner_id"),
                result.getString("idempotency_key"),
                result.getObject("application_id", UUID.class),
                result.getString("job_id"),
                DocumentKind.valueOf(result.getString("document_type")),
                UploadFormat.valueOf(result.getString("file_type")),
                result.getString("request_fingerprint"),
                result.getString("payload_sha256"),
                ApplicationUploadState.valueOf(result.getString("state")),
                result.getObject("store_operation_id", UUID.class),
                result.getString("store_state"),
                result.getObject("document_id", UUID.class),
                applicationVersion == null ? null : applicationVersion.longValue(),
                result.getString("failure_code"),
                result.getString("failure_message"),
                result.getLong("version"),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    private OffsetDateTime atOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String bounded(String message) {
        if (message == null || message.length() <= 300) {
            return message;
        }
        return message.substring(0, 300);
    }
}
