package com.jobseekercopilot.documentgenerationgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyCurrentResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyHistoryResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyPageResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.SelectFamilyCurrentRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.SelectFamilyCurrentRequest.ExpectedCurrentState;
import com.jobseekercopilot.documentgenerationgateway.exception.DocumentHistoryDownstreamException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

class DocumentFamilyHistoryServiceTest {
    private static final String READER = "reader-token";
    private static final String PRODUCER = "producer-token";

    @Test
    void listsAndReadsFamiliesWithReaderIdentityAndBoundOwner() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID familyId = UUID.randomUUID();
        DocumentFamilyPageResponse page = new DocumentFamilyPageResponse(
                List.of(), 0, 20, 0, 0);
        DocumentFamilyHistoryResponse history = new DocumentFamilyHistoryResponse(
                familyId, "job-1", null, null, null, List.of());
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/families?page={page}&size={size}"),
                eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentFamilyPageResponse.class),
                eq(0),
                eq(20))).thenReturn(ResponseEntity.ok(page));
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/families/{familyId}"),
                eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentFamilyHistoryResponse.class),
                eq(familyId))).thenReturn(ResponseEntity.ok(history));
        var service = service(restTemplate);

        assertEquals(page, service.list("owner-1", 0, 20));
        assertEquals(history, service.history("owner-1", familyId));

        ArgumentCaptor<HttpEntity<?>> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://store/api/v1/documents/families/{familyId}"),
                eq(HttpMethod.GET),
                request.capture(),
                eq(DocumentFamilyHistoryResponse.class),
                eq(familyId));
        assertEquals(READER, request.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals("owner-1", request.getValue().getHeaders().getFirst("X-Document-Owner"));
    }

    @Test
    void currentSelectionUsesProducerIdentityExpectedPointerAndIdempotencyKey() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID familyId = UUID.randomUUID();
        UUID selectedId = UUID.randomUUID();
        UUID previousId = UUID.randomUUID();
        SelectFamilyCurrentRequest request = new SelectFamilyCurrentRequest(
                selectedId, ExpectedCurrentState.SELECTED, previousId);
        DocumentFamilyCurrentResponse response = new DocumentFamilyCurrentResponse(
                UUID.randomUUID(), familyId, selectedId, 2, OffsetDateTime.now());
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/families/{familyId}/current"),
                eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentFamilyCurrentResponse.class),
                eq(familyId))).thenReturn(ResponseEntity.ok(response));
        var service = service(restTemplate);

        assertEquals(response, service.selectCurrent(
                "owner-1", familyId, "select-current-2", request));

        ArgumentCaptor<HttpEntity<?>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://store/api/v1/documents/families/{familyId}/current"),
                eq(HttpMethod.PATCH),
                entity.capture(),
                eq(DocumentFamilyCurrentResponse.class),
                eq(familyId));
        assertEquals(PRODUCER, entity.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals("owner-1", entity.getValue().getHeaders().getFirst("X-Document-Owner"));
        assertEquals("select-current-2", entity.getValue().getHeaders().getFirst("Idempotency-Key"));
        assertEquals(request, entity.getValue().getBody());
    }

    @Test
    void preservesNonEnumeratingNotFoundAndConflictStatuses() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID familyId = UUID.randomUUID();
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/families/{familyId}"),
                eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentFamilyHistoryResponse.class),
                eq(familyId))).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        var service = service(restTemplate);

        DocumentHistoryDownstreamException exception = assertThrows(
                DocumentHistoryDownstreamException.class,
                () -> service.history("owner-1", familyId));
        assertEquals(HttpStatus.NOT_FOUND, exception.status());
    }

    private DocumentFamilyHistoryService service(RestTemplate restTemplate) {
        return new DocumentFamilyHistoryService(
                restTemplate, "http://store", READER, PRODUCER);
    }
}
