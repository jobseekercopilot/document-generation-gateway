package com.jobseekercopilot.documentgenerationgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionSlotRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionState;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationFrozenDocumentSelectionState;
import com.jobseekercopilot.documentgenerationgateway.dto.SaveApplicationDocumentSelectionsRequest;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationSelectionDownstreamException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

class ApplicationDocumentSelectionServiceTest {
    private static final String URL =
            "http://tracker/api/v1/applications/{applicationId}/document-selections";

    @Test
    void forwardsAllFourCompleteOptionalSelectionStatesWithBoundIdentity() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID applicationId = UUID.randomUUID();
        UUID cv = UUID.randomUUID();
        UUID letter = UUID.randomUUID();
        ApplicationDocumentSelectionsResponse response = response(
                applicationId, 8, cv, letter);
        when(restTemplate.exchange(
                eq(URL),
                eq(HttpMethod.PUT),
                Mockito.<HttpEntity<?>>any(),
                eq(ApplicationDocumentSelectionsResponse.class),
                eq(applicationId))).thenReturn(ResponseEntity.ok(response));
        ApplicationDocumentSelectionService service = service(restTemplate);
        List<SaveApplicationDocumentSelectionsRequest> requests = List.of(
                request(7, null, null),
                request(7, cv, null),
                request(7, null, letter),
                request(7, cv, letter));

        requests.forEach(request -> assertEquals(
                response,
                service.save(
                        "alice",
                        applicationId,
                        "save-selections-" + requests.indexOf(request),
                        request)));

        ArgumentCaptor<HttpEntity<?>> entities =
                ArgumentCaptor.forClass(HttpEntity.class);
        Mockito.verify(restTemplate, Mockito.times(4)).exchange(
                eq(URL),
                eq(HttpMethod.PUT),
                entities.capture(),
                eq(ApplicationDocumentSelectionsResponse.class),
                eq(applicationId));
        assertEquals(
                List.of(
                        ApplicationDocumentSelectionState.OMITTED,
                        ApplicationDocumentSelectionState.SELECTED,
                        ApplicationDocumentSelectionState.OMITTED,
                        ApplicationDocumentSelectionState.SELECTED),
                entities.getAllValues().stream()
                        .map(entity ->
                                ((SaveApplicationDocumentSelectionsRequest)
                                                entity.getBody())
                                        .cvSelection()
                                        .state())
                        .toList());
        HttpHeaders headers = entities.getAllValues().get(0).getHeaders();
        assertEquals("tracker-token", headers.getFirst("X-Service-Token"));
        assertEquals("alice", headers.getFirst("X-Application-Owner"));
        assertEquals(
                "save-selections-0",
                headers.getFirst("Idempotency-Key"));
    }

    @Test
    void staleConflictRetainsOnlyAuthoritativeSafeCurrentSelection() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID applicationId = UUID.randomUUID();
        UUID cv = UUID.randomUUID();
        String body = """
                {
                  "status":409,
                  "message":"stale",
                  "currentApplication":{
                    "id":"%s",
                    "status":"APPLIED",
                    "version":9,
                    "cvDocumentId":"%s",
                    "applicationUsedCvState":"SELECTED",
                    "applicationUsedCoverLetterState":"OMITTED",
                    "applicationUsedAt":"2026-08-07T06:00:00",
                    "appliedAt":"2026-08-07T06:00:00",
                    "contentSha256":"must-not-propagate"
                  }
                }
                """.formatted(applicationId, cv);
        when(restTemplate.exchange(
                eq(URL),
                eq(HttpMethod.PUT),
                Mockito.<HttpEntity<?>>any(),
                eq(ApplicationDocumentSelectionsResponse.class),
                eq(applicationId))).thenThrow(HttpClientErrorException.create(
                        HttpStatus.CONFLICT,
                        "Conflict",
                        HttpHeaders.EMPTY,
                        body.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        ApplicationSelectionDownstreamException exception = assertThrows(
                ApplicationSelectionDownstreamException.class,
                () -> service(restTemplate).save(
                        "alice",
                        applicationId,
                        "stale-write",
                        request(8, cv, null)));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        assertEquals(9, exception.currentApplication().version());
        assertEquals(cv, exception.currentApplication().cvDocumentId());
        assertNull(exception.currentApplication().coverLetterDocumentId());
        assertEquals(
                ApplicationFrozenDocumentSelectionState.SELECTED,
                exception.currentApplication().applicationUsedCvState());
        assertEquals(
                ApplicationFrozenDocumentSelectionState.OMITTED,
                exception.currentApplication()
                        .applicationUsedCoverLetterState());
        assertEquals(
                exception.currentApplication().applicationUsedAt(),
                exception.currentApplication().appliedAt());
    }

    @Test
    void rejectsPartialSlotsAndUnsafeKeysBeforeCallingTracker() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        ApplicationDocumentSelectionService service = service(restTemplate);
        UUID applicationId = UUID.randomUUID();
        SaveApplicationDocumentSelectionsRequest invalid =
                new SaveApplicationDocumentSelectionsRequest(
                        new ApplicationDocumentSelectionSlotRequest(
                                ApplicationDocumentSelectionState.SELECTED,
                                null),
                        omitted(),
                        0L);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(
                        "alice", applicationId, "valid-key", invalid));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(
                        "alice",
                        applicationId,
                        "x".repeat(129),
                        request(0, null, null)));
        verifyNoInteractions(restTemplate);
    }

    private ApplicationDocumentSelectionService service(
            RestTemplate restTemplate) {
        return new ApplicationDocumentSelectionService(
                restTemplate,
                new ObjectMapper().findAndRegisterModules(),
                "http://tracker",
                "tracker-token");
    }

    private SaveApplicationDocumentSelectionsRequest request(
            long version, UUID cv, UUID letter) {
        return new SaveApplicationDocumentSelectionsRequest(
                slot(cv), slot(letter), version);
    }

    private ApplicationDocumentSelectionSlotRequest slot(UUID documentId) {
        return documentId == null
                ? omitted()
                : new ApplicationDocumentSelectionSlotRequest(
                        ApplicationDocumentSelectionState.SELECTED,
                        documentId);
    }

    private ApplicationDocumentSelectionSlotRequest omitted() {
        return new ApplicationDocumentSelectionSlotRequest(
                ApplicationDocumentSelectionState.OMITTED, null);
    }

    private ApplicationDocumentSelectionsResponse response(
            UUID applicationId, long version, UUID cv, UUID letter) {
        return new ApplicationDocumentSelectionsResponse(
                applicationId,
                "SAVED",
                version,
                cv,
                letter,
                null,
                null,
                null,
                ApplicationFrozenDocumentSelectionState.UNKNOWN,
                null,
                ApplicationFrozenDocumentSelectionState.UNKNOWN,
                null,
                null);
    }
}
