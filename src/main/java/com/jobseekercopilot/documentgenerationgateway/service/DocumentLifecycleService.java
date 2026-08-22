package com.jobseekercopilot.documentgenerationgateway.service;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentApplicationAssociationsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentVersionLifecycleResponse;
import com.jobseekercopilot.documentgenerationgateway.exception.DocumentHistoryDownstreamException;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
public class DocumentLifecycleService {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String DOCUMENT_OWNER_HEADER = "X-Document-Owner";
    private static final String APPLICATION_OWNER_HEADER = "X-Application-Owner";

    private final RestTemplate restTemplate;
    private final String storeBaseUrl;
    private final String trackerBaseUrl;
    private final String storeProducerToken;
    private final String trackerServiceToken;

    @Autowired
    public DocumentLifecycleService(
            RestTemplate restTemplate,
            @Value("${services.document-store-service.base-url}")
            String storeBaseUrl,
            @Value("${services.application-tracker-service.base-url}")
            String trackerBaseUrl,
            DownstreamServiceCredentials credentials) {
        this(
                restTemplate,
                storeBaseUrl,
                trackerBaseUrl,
                credentials.documentStoreProducerToken(),
                credentials.applicationTrackerProducerToken());
    }

    DocumentLifecycleService(
            RestTemplate restTemplate,
            String storeBaseUrl,
            String trackerBaseUrl,
            String storeProducerToken,
            String trackerServiceToken) {
        this.restTemplate = restTemplate;
        this.storeBaseUrl = storeBaseUrl;
        this.trackerBaseUrl = trackerBaseUrl;
        this.storeProducerToken = storeProducerToken;
        this.trackerServiceToken = trackerServiceToken;
    }

    public DocumentApplicationAssociationsResponse associations(
            String ownerId,
            UUID documentId) {
        requireOwner(ownerId);
        try {
            ResponseEntity<DocumentApplicationAssociationsResponse> response =
                    restTemplate.exchange(
                            trackerBaseUrl
                                    + "/api/v1/applications/document/{documentId}/associations",
                            HttpMethod.GET,
                            new HttpEntity<>(trackerHeaders(ownerId)),
                            DocumentApplicationAssociationsResponse.class,
                            documentId);
            return requiredBody(response);
        } catch (HttpStatusCodeException exception) {
            throw new DocumentHistoryDownstreamException(
                    exception.getStatusCode());
        }
    }

    public DocumentVersionLifecycleResponse archive(
            String ownerId,
            UUID documentId) {
        return mutate(ownerId, documentId, HttpMethod.PATCH, "/archive");
    }

    public DocumentVersionLifecycleResponse restore(
            String ownerId,
            UUID documentId) {
        return mutate(ownerId, documentId, HttpMethod.PATCH, "/restore");
    }

    public void delete(String ownerId, UUID documentId) {
        requireOwner(ownerId);
        try {
            restTemplate.exchange(
                    storeBaseUrl + "/api/v1/documents/{documentId}",
                    HttpMethod.DELETE,
                    new HttpEntity<>(storeHeaders(ownerId)),
                    Void.class,
                    documentId);
        } catch (HttpStatusCodeException exception) {
            throw new DocumentHistoryDownstreamException(
                    exception.getStatusCode());
        }
    }

    private DocumentVersionLifecycleResponse mutate(
            String ownerId,
            UUID documentId,
            HttpMethod method,
            String suffix) {
        requireOwner(ownerId);
        try {
            return requiredBody(restTemplate.exchange(
                    storeBaseUrl
                            + "/api/v1/documents/{documentId}"
                            + suffix,
                    method,
                    new HttpEntity<>(storeHeaders(ownerId)),
                    DocumentVersionLifecycleResponse.class,
                    documentId));
        } catch (HttpStatusCodeException exception) {
            throw new DocumentHistoryDownstreamException(
                    exception.getStatusCode());
        }
    }

    private HttpHeaders storeHeaders(String ownerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, storeProducerToken);
        headers.set(DOCUMENT_OWNER_HEADER, ownerId);
        return headers;
    }

    private HttpHeaders trackerHeaders(String ownerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, trackerServiceToken);
        headers.set(APPLICATION_OWNER_HEADER, ownerId);
        return headers;
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated document owner is required.");
        }
    }

    private <T> T requiredBody(ResponseEntity<T> response) {
        if (response.getBody() == null) {
            throw new IllegalStateException(
                    "Lifecycle dependency returned an empty response.");
        }
        return response.getBody();
    }
}
