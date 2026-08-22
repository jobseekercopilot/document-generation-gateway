package com.jobseekercopilot.documentgenerationgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentApplicationAssociationsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentVersionLifecycleResponse;
import com.jobseekercopilot.documentgenerationgateway.exception.DocumentHistoryDownstreamException;
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

class DocumentLifecycleServiceTest {
    private static final String STORE_TOKEN = "store-producer-token";
    private static final String TRACKER_TOKEN = "tracker-service-token";

    @Test
    void readsOwnerScopedAssociationsWithTrackerIdentity() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID documentId = UUID.randomUUID();
        DocumentApplicationAssociationsResponse response =
                new DocumentApplicationAssociationsResponse(
                        documentId, 0, List.of());
        when(restTemplate.exchange(
                eq("http://tracker/api/v1/applications/document/{documentId}/associations"),
                eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentApplicationAssociationsResponse.class),
                eq(documentId))).thenReturn(ResponseEntity.ok(response));

        assertEquals(response, service(restTemplate).associations(
                "owner-1", documentId));

        ArgumentCaptor<HttpEntity<?>> request =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://tracker/api/v1/applications/document/{documentId}/associations"),
                eq(HttpMethod.GET),
                request.capture(),
                eq(DocumentApplicationAssociationsResponse.class),
                eq(documentId));
        assertEquals(TRACKER_TOKEN,
                request.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals("owner-1",
                request.getValue().getHeaders().getFirst("X-Application-Owner"));
    }

    @Test
    void archivesRestoresAndDeletesOnlyOneExactVersionWithStoreIdentity() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID documentId = UUID.randomUUID();
        DocumentVersionLifecycleResponse archived =
                new DocumentVersionLifecycleResponse(
                        documentId,
                        UUID.randomUUID(),
                        null,
                        2,
                        "APPROVED",
                        "ARCHIVED",
                        false,
                        null,
                        null,
                        null,
                        null,
                        null);
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/{documentId}/archive"),
                eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentVersionLifecycleResponse.class),
                eq(documentId))).thenReturn(ResponseEntity.ok(archived));
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/{documentId}/restore"),
                eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentVersionLifecycleResponse.class),
                eq(documentId))).thenReturn(ResponseEntity.ok(archived));
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/{documentId}"),
                eq(HttpMethod.DELETE),
                Mockito.<HttpEntity<?>>any(),
                eq(Void.class),
                eq(documentId))).thenReturn(ResponseEntity.noContent().build());
        DocumentLifecycleService service = service(restTemplate);

        assertEquals(archived, service.archive("owner-1", documentId));
        assertEquals(archived, service.restore("owner-1", documentId));
        service.delete("owner-1", documentId);

        ArgumentCaptor<HttpEntity<?>> request =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://store/api/v1/documents/{documentId}"),
                eq(HttpMethod.DELETE),
                request.capture(),
                eq(Void.class),
                eq(documentId));
        assertEquals(STORE_TOKEN,
                request.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals("owner-1",
                request.getValue().getHeaders().getFirst("X-Document-Owner"));
    }

    @Test
    void preservesNonEnumeratingAndConflictStatuses() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID documentId = UUID.randomUUID();
        when(restTemplate.exchange(
                eq("http://store/api/v1/documents/{documentId}/archive"),
                eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                eq(DocumentVersionLifecycleResponse.class),
                eq(documentId))).thenThrow(
                        new HttpClientErrorException(HttpStatus.CONFLICT));

        DocumentHistoryDownstreamException exception = assertThrows(
                DocumentHistoryDownstreamException.class,
                () -> service(restTemplate).archive("owner-1", documentId));
        assertEquals(HttpStatus.CONFLICT, exception.status());
    }

    private DocumentLifecycleService service(RestTemplate restTemplate) {
        return new DocumentLifecycleService(
                restTemplate,
                "http://store",
                "http://tracker",
                STORE_TOKEN,
                TRACKER_TOKEN);
    }
}
