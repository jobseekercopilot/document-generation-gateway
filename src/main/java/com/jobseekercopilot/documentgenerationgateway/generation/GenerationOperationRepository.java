package com.jobseekercopilot.documentgenerationgateway.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GenerationOperationRepository {
    private static final int APPROVAL_ACCEPT_ATTEMPTS = 3;
    private static final TypeReference<LinkedHashMap<String, Object>> DATA_TYPE =
            new TypeReference<>() {
            };
    private static final String SELECT_COLUMNS = """
            SELECT id, owner_id, idempotency_key, saved_job_id,
                   request_fingerprint, state, data_json, failure_code,
                   failure_message, deadline_at, lease_token, lease_until,
                   version, created_at, updated_at
              FROM generation_operations
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GenerationOperationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public GenerationOperation createOrReplay(
            String ownerId,
            String idempotencyKey,
            UUID savedJobId,
            String requestFingerprint,
            Map<String, Object> initialData,
            Duration deadline) {
        Instant now = Instant.now();
        GenerationOperation created = new GenerationOperation(
                UUID.randomUUID(),
                ownerId,
                idempotencyKey,
                savedJobId,
                requestFingerprint,
                GenerationOperationState.CREATED,
                initialData,
                null,
                null,
                now.plus(deadline),
                null,
                null,
                0,
                now,
                now);
        try {
            jdbc.update("""
                    INSERT INTO generation_operations (
                        id, owner_id, idempotency_key, saved_job_id,
                        request_fingerprint, state, data_json, deadline_at,
                        version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    created.id(),
                    created.ownerId(),
                    created.idempotencyKey(),
                    created.savedJobId(),
                    created.requestFingerprint(),
                    created.state().name(),
                    writeData(created.data()),
                    atOffset(created.deadlineAt()),
                    created.version(),
                    atOffset(created.createdAt()),
                    atOffset(created.updatedAt()));
            return created;
        } catch (DataIntegrityViolationException duplicate) {
            Optional<GenerationOperation> byKey =
                    findByOwnerAndIdempotencyKey(ownerId, idempotencyKey);
            if (byKey.isPresent()) {
                GenerationOperation replay = byKey.get();
                if (!replay.savedJobId().equals(savedJobId)
                        || !replay.requestFingerprint().equals(requestFingerprint)) {
                    throw new GenerationConflictException(
                            "Idempotency-Key was already used for a different generation request.");
                }
                return replay;
            }
            return findLatestReplaySafe(
                    ownerId,
                    savedJobId,
                    requestFingerprint)
                    .orElseThrow(() -> new GenerationConflictException(
                            "An active generation request could not be "
                                    + "reconciled safely."));
        }
    }

    public GenerationOperation createOrReplay(
            String ownerId,
            String idempotencyKey,
            UUID savedJobId,
            String requestFingerprint,
            Duration deadline) {
        return createOrReplay(
                ownerId,
                idempotencyKey,
                savedJobId,
                requestFingerprint,
                Map.of(),
                deadline);
    }

    public Optional<GenerationOperation> findByOwnerAndId(UUID id, String ownerId) {
        return one(
                SELECT_COLUMNS + " WHERE id = ? AND owner_id = ?",
                id,
                ownerId);
    }

    Optional<GenerationOperation> findByOwnerAndIdempotencyKey(
            String ownerId,
            String idempotencyKey) {
        return one(
                SELECT_COLUMNS + " WHERE owner_id = ? AND idempotency_key = ?",
                ownerId,
                idempotencyKey);
    }

    Optional<GenerationOperation> findByOwnerAndSavedJobId(
            String ownerId,
            UUID savedJobId) {
        return one(
                SELECT_COLUMNS + " WHERE owner_id = ? AND saved_job_id = ?",
                ownerId,
                savedJobId);
    }

    public Optional<GenerationOperation> findLatestReplaySafe(
            String ownerId,
            UUID savedJobId,
            String requestFingerprint) {
        return one(
                SELECT_COLUMNS
                        + """
                         WHERE owner_id = ?
                           AND saved_job_id = ?
                           AND request_fingerprint = ?
                           AND state NOT IN (
                               'COMPLETED',
                               'GENERATION_OUTCOME_UNKNOWN',
                               'RECOVERY_REQUIRED',
                               'FAILED',
                               'CANCELLED')
                         ORDER BY created_at DESC
                         LIMIT 1
                        """,
                ownerId,
                savedJobId,
                requestFingerprint);
    }

    public Optional<GenerationOperation>
            findLatestRecoverableApplicationConflict(
                    String ownerId,
                    UUID savedJobId,
                    String requestFingerprint) {
        return one(
                SELECT_COLUMNS
                        + """
                         WHERE owner_id = ?
                           AND saved_job_id = ?
                           AND request_fingerprint = ?
                           AND state = 'RECOVERY_REQUIRED'
                           AND failure_code IN (
                               'APPLICATION_LINK_RECOVERY_REQUIRED',
                               'APPROVAL_REQUEST_REJECTED')
                           AND deadline_at > ?
                         ORDER BY created_at DESC
                         LIMIT 1
                        """,
                ownerId,
                savedJobId,
                requestFingerprint,
                atOffset(Instant.now()));
    }

    public boolean tryAcquire(
            UUID id,
            String ownerId,
            UUID leaseToken,
            Duration leaseDuration) {
        Instant now = Instant.now();
        return jdbc.update("""
                UPDATE generation_operations
                   SET lease_token = ?, lease_until = ?, updated_at = ?
                 WHERE id = ? AND owner_id = ?
                   AND (lease_until IS NULL OR lease_until < ?)
                """,
                leaseToken,
                atOffset(now.plus(leaseDuration)),
                atOffset(now),
                id,
                ownerId,
                atOffset(now)) == 1;
    }

    public GenerationOperation checkpoint(
            GenerationOperation operation,
            UUID leaseToken,
            GenerationOperationState state,
            Map<String, Object> data,
            String failureCode,
            String failureMessage) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE generation_operations
                   SET state = ?, data_json = ?, failure_code = ?,
                       failure_message = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND owner_id = ? AND lease_token = ? AND version = ?
                """,
                state.name(),
                writeData(data),
                failureCode,
                bounded(failureMessage, 500),
                atOffset(now),
                operation.id(),
                operation.ownerId(),
                leaseToken,
                operation.version());
        if (updated != 1) {
            throw new GenerationConflictException(
                    "Generation operation changed while a step was being checkpointed.");
        }
        return findByOwnerAndId(operation.id(), operation.ownerId())
                .orElseThrow();
    }

    public GenerationOperation acceptApproval(
            GenerationOperation operation,
            Map<String, Object> data) {
        Object approvalRequest = data.get("approvalRequest");
        if (approvalRequest == null) {
            throw new GenerationConflictException(
                    "Approval request is required before generation can resume.");
        }

        GenerationOperation current = operation;
        Map<String, Object> candidateData = new LinkedHashMap<>(data);
        for (int attempt = 0;
                attempt < APPROVAL_ACCEPT_ATTEMPTS;
                attempt++) {
            Object persistedRequest =
                    current.data().get("approvalRequest");
            if (persistedRequest != null) {
                if (Objects.equals(persistedRequest, approvalRequest)) {
                    return current;
                }
                throw new GenerationConflictException(
                        "A different approval request was already accepted.");
            }
            if (current.state()
                    != GenerationOperationState.AWAITING_APPROVAL) {
                throw new GenerationConflictException(
                        "Generation operation is not awaiting approval.");
            }

            Instant now = Instant.now();
            int updated = jdbc.update("""
                    UPDATE generation_operations
                       SET data_json = ?, failure_code = NULL,
                           failure_message = NULL, updated_at = ?,
                           version = version + 1
                     WHERE id = ? AND owner_id = ?
                       AND state = 'AWAITING_APPROVAL'
                       AND version = ?
                    """,
                    writeData(candidateData),
                    atOffset(now),
                    current.id(),
                    current.ownerId(),
                    current.version());
            current = findByOwnerAndId(
                            current.id(), current.ownerId())
                    .orElseThrow();
            if (updated == 1) {
                return current;
            }

            Object concurrentRequest =
                    current.data().get("approvalRequest");
            if (concurrentRequest != null) {
                if (Objects.equals(
                        concurrentRequest, approvalRequest)) {
                    return current;
                }
                throw new GenerationConflictException(
                        "A different approval request was already accepted.");
            }
            if (current.state()
                    != GenerationOperationState.AWAITING_APPROVAL) {
                throw new GenerationConflictException(
                        "Generation operation changed while approval "
                                + "was being accepted.");
            }
            candidateData = new LinkedHashMap<>(current.data());
            candidateData.put("approvalRequest", approvalRequest);
        }
        throw new GenerationConflictException(
                "Generation operation changed while approval "
                        + "was being accepted. Retry the request.");
    }

    public void release(UUID id, String ownerId, UUID leaseToken) {
        jdbc.update("""
                UPDATE generation_operations
                   SET lease_token = NULL, lease_until = NULL, updated_at = ?
                 WHERE id = ? AND owner_id = ? AND lease_token = ?
                """,
                atOffset(Instant.now()),
                id,
                ownerId,
                leaseToken);
    }

    private Optional<GenerationOperation> one(String sql, Object... arguments) {
        List<GenerationOperation> rows = jdbc.query(sql, this::map, arguments);
        return rows.stream().findFirst();
    }

    private GenerationOperation map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new GenerationOperation(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("owner_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getObject("saved_job_id", UUID.class),
                resultSet.getString("request_fingerprint").trim(),
                GenerationOperationState.valueOf(resultSet.getString("state")),
                readData(resultSet.getString("data_json")),
                resultSet.getString("failure_code"),
                resultSet.getString("failure_message"),
                instant(resultSet, "deadline_at"),
                resultSet.getObject("lease_token", UUID.class),
                nullableInstant(resultSet, "lease_until"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private String writeData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Generation checkpoint could not be serialized.", exception);
        }
    }

    private Map<String, Object> readData(String json) {
        try {
            return objectMapper.readValue(json, DATA_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Generation checkpoint could not be read.", exception);
        }
    }

    private static OffsetDateTime atOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum);
    }
}
