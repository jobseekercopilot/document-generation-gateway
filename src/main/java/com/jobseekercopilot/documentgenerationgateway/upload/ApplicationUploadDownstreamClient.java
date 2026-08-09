package com.jobseekercopilot.documentgenerationgateway.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
public class ApplicationUploadDownstreamClient {
    private static final String SERVICE_TOKEN = "X-Service-Token";
    private static final String DOCUMENT_OWNER = "X-Document-Owner";
    private static final String APPLICATION_OWNER = "X-Application-Owner";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final DownstreamServiceCredentials credentials;
    private final String storeBaseUrl;
    private final String trackerBaseUrl;

    public ApplicationUploadDownstreamClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            DownstreamServiceCredentials credentials,
            @Value("${services.document-store-service.base-url}") String storeBaseUrl,
            @Value("${services.application-tracker-service.base-url}") String trackerBaseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.storeBaseUrl = storeBaseUrl;
        this.trackerBaseUrl = trackerBaseUrl;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> application(String ownerId, UUID applicationId) {
        HttpHeaders headers = serviceHeaders(
                credentials.applicationTrackerProducerToken(),
                APPLICATION_OWNER,
                ownerId);
        Object body = restTemplate.exchange(
                trackerBaseUrl + "/api/v1/applications/{applicationId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class,
                applicationId).getBody();
        if (!(body instanceof Map<?, ?> application)) {
            throw new IllegalStateException(
                    "Application Tracker returned no application record.");
        }
        Map<String, Object> result = objectMapper.convertValue(
                application, LinkedHashMap.class);
        return applicationId.equals(uuid(result.get("id"))) ? result : null;
    }

    public Map<String, Object> upload(
            ApplicationUploadOperation operation,
            byte[] bytes,
            String originalFilename) {
        HttpHeaders headers = serviceHeaders(
                credentials.documentStoreProducerToken(),
                DOCUMENT_OWNER,
                operation.ownerId());
        headers.set("Idempotency-Key", operation.id() + ":store");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new NamedByteArrayResource(bytes, originalFilename));
        return responseBody(restTemplate.exchange(
                storeBaseUrl
                        + "/api/v1/applications/{applicationId}/documents/"
                        + "{documentType}/uploads?jobId={jobId}&fileType={fileType}",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class,
                operation.applicationId(),
                operation.documentType().name(),
                operation.jobId(),
                operation.fileType().name()));
    }

    public Map<String, Object> saveSelections(
            ApplicationUploadOperation operation,
            long expectedVersion,
            UUID cvDocumentId,
            UUID coverLetterDocumentId) {
        HttpHeaders headers = serviceHeaders(
                credentials.applicationTrackerProducerToken(),
                APPLICATION_OWNER,
                operation.ownerId());
        headers.set("Idempotency-Key", operation.id() + ":link");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return responseBody(restTemplate.exchange(
                trackerBaseUrl
                        + "/api/v1/applications/{applicationId}/document-selections",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "cvSelection", selection(cvDocumentId),
                        "coverLetterSelection", selection(coverLetterDocumentId),
                        "expectedVersion", expectedVersion), headers),
                Map.class,
                operation.applicationId()));
    }

    private Map<String, Object> selection(UUID documentId) {
        return documentId == null
                ? Map.of("state", "OMITTED")
                : Map.of("state", "SELECTED", "documentId", documentId);
    }

    private HttpHeaders serviceHeaders(
            String token, String ownerHeader, String ownerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN, token);
        headers.set(ownerHeader, ownerId);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseBody(
            org.springframework.http.ResponseEntity<Map> response) {
        if (response.getBody() == null) {
            throw new IllegalStateException(
                    "A downstream service returned no response body.");
        }
        return new LinkedHashMap<>((Map<String, Object>) response.getBody());
    }

    private UUID uuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String string) {
            try {
                return UUID.fromString(string);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename == null || filename.isBlank()
                    ? "upload"
                    : filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
