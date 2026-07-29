package com.jobseekercopilot.documentgenerationgateway.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import com.jobseekercopilot.generated.userprofileservice.api.EvidenceSnapshotsApi;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import com.jobseekercopilot.generated.userprofileservice.model.EvidenceCategory;
import com.jobseekercopilot.generated.userprofileservice.model.EvidenceSnapshotPurpose;
import com.jobseekercopilot.generated.userprofileservice.model.EvidenceSnapshotRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class RestGenerationDownstreamClient
        implements GenerationDownstreamClient {
    private static final String SERVICE_TOKEN = "X-Service-Token";
    private static final String DOCUMENT_OWNER = "X-Document-Owner";
    private static final String PAYMENT_OWNER = "X-Payment-Owner";
    private static final String APPLICATION_OWNER = "X-Application-Owner";

    private final RestTemplate restTemplate;
    private final UserProfilesApi profilesApi;
    private final EvidenceSnapshotsApi evidenceSnapshotsApi;
    private final ObjectMapper objectMapper;
    private final DownstreamServiceCredentials credentials;
    private final String jobBaseUrl;
    private final String authenticationBaseUrl;
    private final String cvBaseUrl;
    private final String paymentBaseUrl;
    private final String storeBaseUrl;
    private final String exportBaseUrl;
    private final String trackerBaseUrl;

    public RestGenerationDownstreamClient(
            RestTemplate restTemplate,
            UserProfilesApi profilesApi,
            EvidenceSnapshotsApi evidenceSnapshotsApi,
            ObjectMapper objectMapper,
            DownstreamServiceCredentials credentials,
            @Value("${services.job-service.base-url}") String jobBaseUrl,
            @Value("${services.authentication-service.base-url}")
            String authenticationBaseUrl,
            @Value("${services.cv-cover-letter-service.base-url}")
            String cvBaseUrl,
            @Value("${services.payment-service.base-url}") String paymentBaseUrl,
            @Value("${services.document-store-service.base-url}")
            String storeBaseUrl,
            @Value("${services.document-export-service.base-url}")
            String exportBaseUrl,
            @Value("${services.application-tracker-service.base-url}")
            String trackerBaseUrl) {
        this.restTemplate = restTemplate;
        this.profilesApi = profilesApi;
        this.evidenceSnapshotsApi = evidenceSnapshotsApi;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.jobBaseUrl = jobBaseUrl;
        this.authenticationBaseUrl = authenticationBaseUrl;
        this.cvBaseUrl = cvBaseUrl;
        this.paymentBaseUrl = paymentBaseUrl;
        this.storeBaseUrl = storeBaseUrl;
        this.exportBaseUrl = exportBaseUrl;
        this.trackerBaseUrl = trackerBaseUrl;
    }

    @Override
    public Map<String, Object> savedJob(
            UUID savedJobId,
            String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return body(restTemplate.exchange(
                jobBaseUrl + "/api/jobs/saved/{savedJobId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class,
                savedJobId));
    }

    @Override
    public Map<String, Object> profile() {
        Object profile = Objects.requireNonNull(
                profilesApi.getMyProfile(),
                "User Profile Service returned no profile.");
        return objectMapper.convertValue(profile, LinkedHashMap.class);
    }

    @Override
    public Map<String, Object> evidenceSnapshot(
            DocumentEvidenceSelection selection) {
        EvidenceSnapshotRequest request = new EvidenceSnapshotRequest()
                .purpose(EvidenceSnapshotPurpose.valueOf(
                        selection.purpose().name()))
                .entryIds(selection.entryIds())
                .sectionOrder(selection.sectionOrder().stream()
                        .map(section -> EvidenceCategory.valueOf(section.name()))
                        .toList());
        Object snapshot = Objects.requireNonNull(
                evidenceSnapshotsApi.createEvidenceSnapshot(request),
                "User Profile Service returned no evidence snapshot.");
        return objectMapper.convertValue(snapshot, LinkedHashMap.class);
    }

    @Override
    public Map<String, Object> account(String authorization) {
        HttpHeaders headers = serviceHeaders(
                credentials.authenticationServiceToken(),
                null,
                null);
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return body(restTemplate.exchange(
                authenticationBaseUrl + "/api/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class));
    }

    @Override
    public long estimate(String ownerId, Map<String, Object> request) {
        Map<String, Object> response = post(
                cvBaseUrl + "/api/v1/cv-cover-letter/drafts/estimate",
                serviceHeaders(
                        credentials.cvCoverLetterServiceToken(),
                        DOCUMENT_OWNER,
                        ownerId),
                request);
        Number estimated = requiredNumber(response, "estimatedTokens");
        if (estimated.longValue() < 1) {
            throw new IllegalStateException(
                    "CV service returned an invalid token estimate.");
        }
        return estimated.longValue();
    }

    @Override
    public Map<String, Object> reserve(
            String ownerId,
            UUID operationId,
            long estimatedTokens) {
        return post(
                paymentBaseUrl + "/api/v1/payments/reservations",
                serviceHeaders(
                        credentials.paymentServiceToken(),
                        PAYMENT_OWNER,
                        ownerId),
                Map.of(
                        "feature", "CV_AND_COVER_LETTER_GENERATION",
                        "estimatedTokens", estimatedTokens,
                        "operationKey", operationId + ":reservation",
                        "referenceType", "GENERATION_OPERATION",
                        "referenceId", operationId.toString()));
    }

    @Override
    public Map<String, Object> generate(
            String ownerId,
            UUID operationId,
            Map<String, Object> request) {
        HttpHeaders headers = serviceHeaders(
                credentials.cvCoverLetterServiceToken(),
                DOCUMENT_OWNER,
                ownerId);
        headers.set("X-Generation-Operation-Id", operationId.toString());
        return post(
                cvBaseUrl + "/api/v1/cv-cover-letter/drafts",
                headers,
                request);
    }

    @Override
    public void commit(
            String ownerId,
            UUID reservationId,
            long actualTokens) {
        post(
                paymentBaseUrl
                        + "/api/v1/payments/reservations/"
                        + reservationId
                        + "/commit",
                serviceHeaders(
                        credentials.paymentServiceToken(),
                        PAYMENT_OWNER,
                        ownerId),
                Map.of("actualTokens", actualTokens));
    }

    @Override
    public void release(
            String ownerId,
            UUID reservationId,
            String reason) {
        post(
                paymentBaseUrl
                        + "/api/v1/payments/reservations/"
                        + reservationId
                        + "/release",
                serviceHeaders(
                        credentials.paymentServiceToken(),
                        PAYMENT_OWNER,
                        ownerId),
                Map.of("reason", reason));
    }

    @Override
    public Map<String, Object> createDocument(
            String ownerId,
            String idempotencyKey,
            Map<String, Object> request) {
        HttpHeaders headers = serviceHeaders(
                credentials.documentStoreProducerToken(),
                DOCUMENT_OWNER,
                ownerId);
        headers.set("Idempotency-Key", idempotencyKey);
        return post(
                storeBaseUrl + "/api/v1/documents",
                headers,
                request);
    }

    @Override
    public Map<String, Object> approveDocument(
            String ownerId,
            UUID documentId) {
        HttpHeaders headers = serviceHeaders(
                credentials.documentStoreProducerToken(),
                DOCUMENT_OWNER,
                ownerId);
        return body(restTemplate.exchange(
                storeBaseUrl
                        + "/api/v1/documents/{documentId}/approve",
                HttpMethod.PATCH,
                new HttpEntity<>(headers),
                Map.class,
                documentId));
    }

    @Override
    public Map<String, Object> exportDocument(
            String ownerId,
            UUID documentId,
            String idempotencyKey) {
        HttpHeaders headers = serviceHeaders(
                credentials.documentExportServiceToken(),
                DOCUMENT_OWNER,
                ownerId);
        headers.set("Idempotency-Key", idempotencyKey);
        return post(
                exportBaseUrl
                        + "/api/v1/document-exports/documents/"
                        + documentId,
                headers,
                Map.of("formats", List.of("DOCX", "PDF")));
    }

    @Override
    public Map<String, Object> createApplication(
            String ownerId,
            String idempotencyKey,
            Map<String, Object> request) {
        HttpHeaders headers = serviceHeaders(
                credentials.applicationTrackerProducerToken(),
                APPLICATION_OWNER,
                ownerId);
        headers.set("Idempotency-Key", idempotencyKey);
        return post(
                trackerBaseUrl + "/api/v1/applications",
                headers,
                request);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> applications(String ownerId) {
        HttpHeaders headers = serviceHeaders(
                credentials.applicationTrackerProducerToken(),
                APPLICATION_OWNER,
                ownerId);
        Object body = restTemplate.exchange(
                trackerBaseUrl + "/api/v1/applications/user/{userId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                List.class,
                ownerId).getBody();
        if (!(body instanceof List<?> applications)) {
            throw new IllegalStateException(
                    "Application Tracker returned no application list.");
        }
        return applications.stream()
                .map(application -> objectMapper.convertValue(
                        application,
                        LinkedHashMap.class))
                .map(application ->
                        (Map<String, Object>) application)
                .toList();
    }

    @Override
    public Map<String, Object> updateApplicationDocument(
            String ownerId,
            UUID applicationId,
            String documentType,
            UUID documentId) {
        HttpHeaders headers = serviceHeaders(
                credentials.applicationTrackerProducerToken(),
                APPLICATION_OWNER,
                ownerId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return body(restTemplate.exchange(
                trackerBaseUrl
                        + "/api/v1/applications/{applicationId}/document-reference",
                HttpMethod.PATCH,
                new HttpEntity<>(
                        Map.of(
                                "documentType", documentType,
                                "documentId", documentId),
                        headers),
                Map.class,
                applicationId));
    }

    private Map<String, Object> post(
            String url,
            HttpHeaders headers,
            Map<String, Object> request) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return body(restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class));
    }

    private HttpHeaders serviceHeaders(
            String token,
            String ownerHeader,
            String ownerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN, token);
        if (ownerHeader != null) {
            headers.set(ownerHeader, ownerId);
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(
            org.springframework.http.ResponseEntity<Map> response) {
        if (response.getBody() == null) {
            throw new IllegalStateException(
                    "A downstream service returned no response body.");
        }
        return new LinkedHashMap<>((Map<String, Object>) response.getBody());
    }

    private Number requiredNumber(
            Map<String, Object> response,
            String field) {
        Object value = response.get(field);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalStateException(
                "A downstream service omitted " + field + ".");
    }
}
