package com.jobseekercopilot.documentgenerationgateway.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationSelectionConflictResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void exposesSafeCurrentSelectionForStaleVersionConflict() {
        UUID applicationId = UUID.randomUUID();
        ApplicationDocumentSelectionsResponse current =
                new ApplicationDocumentSelectionsResponse(
                        applicationId,
                        "SAVED",
                        4,
                        null,
                        null,
                        null,
                        null);

        ResponseEntity<?> response = handler.applicationSelectionFailure(
                new ApplicationSelectionDownstreamException(
                        HttpStatus.CONFLICT,
                        current));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApplicationSelectionConflictResponse body =
                (ApplicationSelectionConflictResponse) response.getBody();
        assertEquals(current, body.currentApplication());
    }

    @Test
    void normalizesUnexpectedTrackerFailureToServiceUnavailable() {
        ResponseEntity<?> response = handler.applicationSelectionFailure(
                new ApplicationSelectionDownstreamException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("APPLICATION_SELECTION_UNAVAILABLE", body.get("error"));
    }
}
