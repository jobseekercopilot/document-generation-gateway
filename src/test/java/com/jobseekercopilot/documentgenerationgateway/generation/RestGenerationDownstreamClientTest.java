package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class RestGenerationDownstreamClientTest {
    private static final String OWNER = "candidate-123";
    private static final String PAYMENT_TOKEN =
            "payment-token-000000000000000000000001";
    private static final String CV_TOKEN =
            "cv-token-0000000000000000000000000001";
    private static final String EXPORT_TOKEN =
            "export-token-00000000000000000000000001";

    @Mock private RestTemplate restTemplate;
    @Mock private UserProfilesApi profilesApi;

    private RestGenerationDownstreamClient client;

    @BeforeEach
    void setUp() {
        client = new RestGenerationDownstreamClient(
                restTemplate,
                profilesApi,
                new ObjectMapper(),
                new DownstreamServiceCredentials(
                        "auth-token-0000000000000000000000000001",
                        "tracker-token-00000000000000000000000001",
                        CV_TOKEN,
                        EXPORT_TOKEN,
                        "store-producer-00000000000000000000001",
                        "store-reader-0000000000000000000000001",
                        PAYMENT_TOKEN),
                "http://job",
                "http://auth",
                "http://cv",
                "http://payment",
                "http://store",
                "http://export",
                "http://tracker");
    }

    @Test
    void reservationUsesOnlyTheDedicatedPaymentIdentityAndOwner() {
        UUID operationId = UUID.randomUUID();
        when(restTemplate.exchange(
                eq("http://payment/api/v1/payments/reservations"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "reservationId", UUID.randomUUID().toString())));

        client.reserve(OWNER, operationId, 1200);

        HttpEntity<?> request = capturedPost(
                "http://payment/api/v1/payments/reservations");
        assertSingleHeader(request, "X-Service-Token", PAYMENT_TOKEN);
        assertSingleHeader(request, "X-Payment-Owner", OWNER);
        assertNull(request.getHeaders().getFirst("X-Document-Owner"));
        Map<?, ?> body = (Map<?, ?>) request.getBody();
        assertEquals(
                operationId + ":reservation",
                body.get("operationKey"));
        assertEquals(1200L, body.get("estimatedTokens"));
    }

    @Test
    void generationBindsOwnerAndTheDurableOperationId() {
        UUID operationId = UUID.randomUUID();
        when(restTemplate.exchange(
                eq("http://cv/api/v1/cv-cover-letter/drafts"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "operationId", operationId.toString())));

        client.generate(
                OWNER,
                operationId,
                Map.of("inputSchemaVersion", "1.0"));

        HttpEntity<?> request = capturedPost(
                "http://cv/api/v1/cv-cover-letter/drafts");
        assertSingleHeader(request, "X-Service-Token", CV_TOKEN);
        assertSingleHeader(request, "X-Document-Owner", OWNER);
        assertSingleHeader(
                request,
                "X-Generation-Operation-Id",
                operationId.toString());
        assertNull(request.getHeaders().getFirst("X-Payment-Owner"));
    }

    @Test
    void exportBindsOwnerAndStableReplayKey() {
        UUID documentId = UUID.randomUUID();
        String replayKey = UUID.randomUUID() + ":cv-export";
        when(restTemplate.exchange(
                eq("http://export/api/v1/document-exports/documents/"
                        + documentId),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "documentId", documentId.toString())));

        client.exportDocument(OWNER, documentId, replayKey);

        HttpEntity<?> request = capturedPost(
                "http://export/api/v1/document-exports/documents/"
                        + documentId);
        assertSingleHeader(request, "X-Service-Token", EXPORT_TOKEN);
        assertSingleHeader(request, "X-Document-Owner", OWNER);
        assertSingleHeader(request, "Idempotency-Key", replayKey);
        assertNull(request.getHeaders().getFirst("X-Payment-Owner"));
        assertEquals(
                java.util.List.of("DOCX", "PDF"),
                ((Map<?, ?>) request.getBody()).get("formats"));
    }

    private HttpEntity<?> capturedPost(String url) {
        ArgumentCaptor<HttpEntity> request =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq(url),
                eq(HttpMethod.POST),
                request.capture(),
                eq(Map.class));
        return request.getValue();
    }

    private void assertSingleHeader(
            HttpEntity<?> request,
            String name,
            String expected) {
        assertEquals(
                java.util.List.of(expected),
                request.getHeaders().get(name));
    }
}
