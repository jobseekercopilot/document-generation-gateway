package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentPurpose;
import com.jobseekercopilot.documentgenerationgateway.dto.EvidenceSection;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import com.jobseekercopilot.generated.userprofileservice.api.EvidenceSnapshotsApi;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import com.jobseekercopilot.generated.userprofileservice.model.EvidenceSnapshot;
import com.jobseekercopilot.generated.userprofileservice.model.EvidenceSnapshotRequest;
import java.util.List;
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
    @Mock private EvidenceSnapshotsApi evidenceSnapshotsApi;

    private RestGenerationDownstreamClient client;

    @BeforeEach
    void setUp() {
        client = new RestGenerationDownstreamClient(
                restTemplate,
                profilesApi,
                evidenceSnapshotsApi,
                new ObjectMapper().findAndRegisterModules(),
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
    void evidenceSnapshotPreservesClaimantEntryAndSectionOrder() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(evidenceSnapshotsApi.createEvidenceSnapshot(
                any(EvidenceSnapshotRequest.class)))
                .thenReturn(new EvidenceSnapshot(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "a".repeat(64),
                        List.of(
                                com.jobseekercopilot.generated
                                        .userprofileservice.model
                                        .EvidenceCategory.PROJECT,
                                com.jobseekercopilot.generated
                                        .userprofileservice.model
                                        .EvidenceCategory.VOLUNTEERING),
                        List.of(),
                        "b".repeat(64),
                        java.time.OffsetDateTime.now())
                        .purpose(com.jobseekercopilot.generated
                                .userprofileservice.model
                                .EvidenceSnapshotPurpose.CV));

        client.evidenceSnapshot(new DocumentEvidenceSelection(
                DocumentPurpose.CV,
                List.of(first, second),
                List.of(
                        EvidenceSection.PROJECT,
                        EvidenceSection.VOLUNTEERING)));

        ArgumentCaptor<EvidenceSnapshotRequest> request =
                ArgumentCaptor.forClass(EvidenceSnapshotRequest.class);
        verify(evidenceSnapshotsApi).createEvidenceSnapshot(
                request.capture());
        assertEquals(List.of(first, second),
                request.getValue().getEntryIds());
        assertEquals(
                List.of("PROJECT", "VOLUNTEERING"),
                request.getValue().getSectionOrder().stream()
                        .map(Enum::name)
                        .toList());
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

    @Test
    void statusUpdateBindsOwnerAndObservedApplicationVersion() {
        UUID applicationId = UUID.randomUUID();
        when(restTemplate.exchange(
                eq("http://tracker/api/v1/applications/{applicationId}"
                        + "/status"),
                eq(HttpMethod.PATCH),
                any(HttpEntity.class),
                eq(Map.class),
                eq(applicationId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", applicationId.toString(),
                        "status", "DOCUMENTS_GENERATED",
                        "version", 8)));

        client.updateApplicationStatus(
                OWNER,
                applicationId,
                "DOCUMENTS_GENERATED",
                7);

        ArgumentCaptor<HttpEntity> request =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://tracker/api/v1/applications/{applicationId}"
                        + "/status"),
                eq(HttpMethod.PATCH),
                request.capture(),
                eq(Map.class),
                eq(applicationId));
        assertSingleHeader(
                request.getValue(),
                "X-Service-Token",
                "tracker-token-00000000000000000000000001");
        assertSingleHeader(
                request.getValue(),
                "X-Application-Owner",
                OWNER);
        Map<?, ?> body = (Map<?, ?>) request.getValue().getBody();
        assertEquals("DOCUMENTS_GENERATED", body.get("status"));
        assertEquals(7L, body.get("expectedVersion"));
    }

    @Test
    void atomicSelectionsBindOwnerReplayKeyAndCompleteDesiredState() {
        UUID applicationId = UUID.randomUUID();
        UUID cvDocumentId = UUID.randomUUID();
        UUID coverLetterDocumentId = UUID.randomUUID();
        when(restTemplate.exchange(
                eq("http://tracker/api/v1/applications/{applicationId}"
                        + "/document-selections"),
                eq(HttpMethod.PUT),
                any(HttpEntity.class),
                eq(Map.class),
                eq(applicationId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", applicationId.toString(),
                        "status", "SAVED",
                        "version", 8)));

        client.updateApplicationDocumentSelections(
                OWNER,
                applicationId,
                "operation-1:application-document-selections",
                7,
                cvDocumentId,
                coverLetterDocumentId);

        ArgumentCaptor<HttpEntity> request =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://tracker/api/v1/applications/{applicationId}"
                        + "/document-selections"),
                eq(HttpMethod.PUT),
                request.capture(),
                eq(Map.class),
                eq(applicationId));
        assertSingleHeader(
                request.getValue(),
                "X-Service-Token",
                "tracker-token-00000000000000000000000001");
        assertSingleHeader(
                request.getValue(),
                "X-Application-Owner",
                OWNER);
        assertSingleHeader(
                request.getValue(),
                "Idempotency-Key",
                "operation-1:application-document-selections");
        Map<?, ?> body = (Map<?, ?>) request.getValue().getBody();
        assertEquals(7L, body.get("expectedVersion"));
        assertEquals(
                Map.of("state", "SELECTED", "documentId", cvDocumentId),
                body.get("cvSelection"));
        assertEquals(
                Map.of(
                        "state",
                        "SELECTED",
                        "documentId",
                        coverLetterDocumentId),
                body.get("coverLetterSelection"));
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
