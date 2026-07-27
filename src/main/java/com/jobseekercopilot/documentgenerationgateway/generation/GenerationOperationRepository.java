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
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GenerationOperationRepository {
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
            Duration deadline) {
        Instant now = Instant.now();
        GenerationOperation created = new GenerationOperation(
                UUID.randomUUID(),
                ownerId,
                idempotencyKey,
                savedJobId,
                requestFingerprint,
                GenerationOperationState.CREATED,
                Map.of(),
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
                    "{}",
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
            return findByOwnerAndSavedJobId(ownerId, savedJobId)
                    .orElseThrow(() -> duplicate);
        }
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
