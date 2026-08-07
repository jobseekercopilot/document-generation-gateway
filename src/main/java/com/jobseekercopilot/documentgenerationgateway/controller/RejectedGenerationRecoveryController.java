package com.jobseekercopilot.documentgenerationgateway.controller;

import com.jobseekercopilot.documentgenerationgateway.dto.GenerationOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.generation.DurableGenerationService;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Hidden
@RequestMapping(
        "/internal/v1/document-generation/rejected-generations")
public class RejectedGenerationRecoveryController {
    private static final int MINIMUM_TOKEN_BYTES = 32;

    private final DurableGenerationService generationService;
    private final boolean enabled;
    private final String operatorToken;

    public RejectedGenerationRecoveryController(
            DurableGenerationService generationService,
            @Value("${document-generation.retained-response-recovery.enabled:false}")
            boolean enabled,
            @Value("${document-generation.retained-response-recovery.operator-token:}")
            String operatorToken) {
        this.generationService = generationService;
        this.enabled = enabled;
        this.operatorToken = operatorToken;
    }

    @PostMapping("/{operationId}/recover")
    public ResponseEntity<GenerationOperationResponse> recover(
            @PathVariable UUID operationId,
            @RequestHeader("X-Operator-Token") String suppliedToken,
            @RequestHeader("X-Document-Owner") String ownerId) {
        requireAuthorized(suppliedToken);
        return ResponseEntity.accepted().body(
                generationService.recoverRejectedGeneration(
                        ownerId, operationId));
    }

    private void requireAuthorized(String suppliedToken) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (operatorToken == null
                || operatorToken.getBytes(StandardCharsets.UTF_8).length
                < MINIMUM_TOKEN_BYTES
                || suppliedToken == null
                || !MessageDigest.isEqual(
                        operatorToken.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
