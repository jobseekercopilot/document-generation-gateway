package com.jobseekercopilot.documentgenerationgateway.generation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record GenerationOperation(
        UUID id,
        String ownerId,
        String idempotencyKey,
        UUID savedJobId,
        String requestFingerprint,
        GenerationOperationState state,
        Map<String, Object> data,
        String failureCode,
        String failureMessage,
        Instant deadlineAt,
        UUID leaseToken,
        Instant leaseUntil,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public GenerationOperation {
        data = data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);
    }

    public GenerationOperation withState(
            GenerationOperationState nextState,
            Map<String, Object> nextData,
            String nextFailureCode,
            String nextFailureMessage) {
        return new GenerationOperation(
                id,
                ownerId,
                idempotencyKey,
                savedJobId,
                requestFingerprint,
                nextState,
                nextData,
                nextFailureCode,
                nextFailureMessage,
                deadlineAt,
                leaseToken,
                leaseUntil,
                version,
                createdAt,
                updatedAt);
    }
}
