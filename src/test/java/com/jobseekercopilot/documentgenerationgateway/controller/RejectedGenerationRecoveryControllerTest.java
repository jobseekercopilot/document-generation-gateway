package com.jobseekercopilot.documentgenerationgateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentgenerationgateway.dto.GenerationOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.generation.DurableGenerationService;
import com.jobseekercopilot.documentgenerationgateway.generation.GenerationOperationState;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class RejectedGenerationRecoveryControllerTest {
    private static final String TOKEN =
            "operator-token-000000000000000000000001";

    @Test
    void failsClosedWhenRecoveryIsDisabledOrTheTokenIsWrong() {
        DurableGenerationService service =
                mock(DurableGenerationService.class);
        UUID operationId = UUID.randomUUID();
        var disabled = new RejectedGenerationRecoveryController(
                service, false, TOKEN);
        var notFound = assertThrows(
                ResponseStatusException.class,
                () -> disabled.recover(
                        operationId, TOKEN, "candidate-123"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        var enabled = new RejectedGenerationRecoveryController(
                service, true, TOKEN);
        var unauthorized = assertThrows(
                ResponseStatusException.class,
                () -> enabled.recover(
                        operationId, "wrong-token", "candidate-123"));
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                unauthorized.getStatusCode());
    }

    @Test
    void delegatesAnAuthorizedRecoveryToTheOwnerScopedService() {
        DurableGenerationService service =
                mock(DurableGenerationService.class);
        UUID operationId = UUID.randomUUID();
        GenerationOperationResponse expected =
                new GenerationOperationResponse(
                        operationId,
                        UUID.randomUUID(),
                        GenerationOperationState.DRAFT_GENERATED,
                        true,
                        false,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        Instant.now().plusSeconds(600),
                        Instant.now(),
                        Instant.now());
        when(service.recoverRejectedGeneration(
                "candidate-123", operationId)).thenReturn(expected);
        var controller = new RejectedGenerationRecoveryController(
                service, true, TOKEN);

        var response = controller.recover(
                operationId, TOKEN, "candidate-123");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(expected, response.getBody());
        verify(service).recoverRejectedGeneration(
                "candidate-123", operationId);
    }
}
