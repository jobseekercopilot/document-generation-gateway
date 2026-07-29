package com.jobseekercopilot.documentgenerationgateway.generation;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import java.util.Map;
import java.util.UUID;

public interface GenerationDownstreamClient {
    Map<String, Object> savedJob(UUID savedJobId, String authorization);

    Map<String, Object> profile();

    Map<String, Object> evidenceSnapshot(DocumentEvidenceSelection selection);

    Map<String, Object> account(String authorization);

    long estimate(String ownerId, Map<String, Object> request);

    Map<String, Object> reserve(
            String ownerId,
            UUID operationId,
            long estimatedTokens);

    Map<String, Object> generate(
            String ownerId,
            UUID operationId,
            Map<String, Object> request);

    void commit(String ownerId, UUID reservationId, long actualTokens);

    void release(String ownerId, UUID reservationId, String reason);

    Map<String, Object> createDocument(
            String ownerId,
            String idempotencyKey,
            Map<String, Object> request);

    Map<String, Object> approveDocument(String ownerId, UUID documentId);

    Map<String, Object> exportDocument(
            String ownerId,
            UUID documentId,
            String idempotencyKey);

    Map<String, Object> createApplication(
            String ownerId,
            String idempotencyKey,
            Map<String, Object> request);
}
