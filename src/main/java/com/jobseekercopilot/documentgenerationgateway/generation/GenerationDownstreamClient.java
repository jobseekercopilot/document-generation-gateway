package com.jobseekercopilot.documentgenerationgateway.generation;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentPurpose;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface GenerationDownstreamClient {
    Map<String, Object> savedJob(UUID savedJobId, String authorization);

    Map<String, Object> profile();

    Map<String, Object> evidenceSnapshot(DocumentEvidenceSelection selection);

    Map<String, Object> account(String authorization);

    long estimate(String ownerId, Map<String, Object> request);

    long estimateSelected(
            String ownerId,
            DocumentPurpose output,
            Map<String, Object> request);

    Map<String, Object> reserve(
            String ownerId,
            UUID operationId,
            long estimatedTokens,
            boolean regeneration);

    Map<String, Object> reserveSelected(
            String ownerId,
            UUID operationId,
            DocumentPurpose output,
            long estimatedTokens,
            boolean regeneration);

    Map<String, Object> reserveStoredSelectedRecovery(
            String ownerId,
            UUID operationId,
            DocumentPurpose output,
            boolean regeneration);

    Map<String, Object> reserveStoredLegacyRecovery(
            String ownerId,
            UUID operationId,
            boolean regeneration);

    Map<String, Object> reserveRetainedResponseRecovery(
            String ownerId,
            UUID operationId,
            long actualTokens);

    Map<String, Object> generate(
            String ownerId,
            UUID operationId,
            Map<String, Object> request);

    Map<String, Object> generateSelected(
            String ownerId,
            UUID operationId,
            DocumentPurpose output,
            Map<String, Object> request);

    Map<String, Object> replayRejectedGeneration(
            String ownerId,
            UUID operationId,
            Map<String, Object> request);

    Map<String, Object> replayRejectedSelectedGeneration(
            String ownerId,
            UUID operationId,
            DocumentPurpose output,
            Map<String, Object> request);

    Map<String, Object> deterministicSelectedFallback(
            String ownerId,
            UUID operationId,
            DocumentPurpose output,
            Map<String, Object> request);

    void commit(
            String ownerId,
            UUID reservationId,
            long actualTokens,
            List<DeliveredDocumentEvidence> deliveries);

    void release(String ownerId, UUID reservationId, String reason);

    Map<String, Object> createDocument(
            String ownerId,
            String idempotencyKey,
            Map<String, Object> request);

    Map<String, Object> approveDocument(String ownerId, UUID documentId);

    Map<String, Object> exportDocument(
            String ownerId,
            UUID documentId,
            String idempotencyKey,
            Map<String, Object> professionalContact);

    Map<String, Object> createApplication(
            String ownerId,
            String idempotencyKey,
            Map<String, Object> request);

    List<Map<String, Object>> applications(String ownerId);

    Map<String, Object> updateApplicationDocumentSelections(
            String ownerId,
            UUID applicationId,
            String idempotencyKey,
            long expectedVersion,
            UUID cvDocumentId,
            UUID coverLetterDocumentId);

    Map<String, Object> updateApplicationStatus(
            String ownerId,
            UUID applicationId,
            String status,
            long expectedVersion);
}
