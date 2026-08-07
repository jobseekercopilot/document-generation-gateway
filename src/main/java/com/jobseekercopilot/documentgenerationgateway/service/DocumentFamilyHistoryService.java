package com.jobseekercopilot.documentgenerationgateway.service;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyCurrentResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyHistoryResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentFamilyPageResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.SelectFamilyCurrentRequest;
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
public class DocumentFamilyHistoryService {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String OWNER_HEADER = "X-Document-Owner";

    private final RestTemplate restTemplate;
    private final String storeBaseUrl;
    private final String readerToken;
    private final String producerToken;

    @Autowired
    public DocumentFamilyHistoryService(
            RestTemplate restTemplate,
            @Value("${services.document-store-service.base-url}") String storeBaseUrl,
            DownstreamServiceCredentials credentials) {
        this(
                restTemplate,
                storeBaseUrl,
                credentials.documentStoreReaderToken(),
                credentials.documentStoreProducerToken());
    }

    DocumentFamilyHistoryService(
            RestTemplate restTemplate,
            String storeBaseUrl,
            String readerToken,
            String producerToken) {
        this.restTemplate = restTemplate;
        this.storeBaseUrl = storeBaseUrl;
        this.readerToken = readerToken;
        this.producerToken = producerToken;
    }

    public DocumentFamilyPageResponse list(String ownerId, int page, int size) {
        requireOwner(ownerId);
        try {
            return requiredBody(restTemplate.exchange(
                    storeBaseUrl + "/api/v1/documents/families?page={page}&size={size}",
                    HttpMethod.GET,
                    new HttpEntity<>(headers(ownerId, readerToken, null)),
                    DocumentFamilyPageResponse.class,
                    page,
                    size));
        } catch (HttpStatusCodeException exception) {
            throw new DocumentHistoryDownstreamException(exception.getStatusCode());
        }
    }

    public DocumentFamilyHistoryResponse history(
            String ownerId, UUID documentFamilyId) {
        requireOwner(ownerId);
        try {
            return requiredBody(restTemplate.exchange(
                    storeBaseUrl + "/api/v1/documents/families/{familyId}",
                    HttpMethod.GET,
                    new HttpEntity<>(headers(ownerId, readerToken, null)),
                    DocumentFamilyHistoryResponse.class,
                    documentFamilyId));
        } catch (HttpStatusCodeException exception) {
            throw new DocumentHistoryDownstreamException(exception.getStatusCode());
        }
    }

    public DocumentFamilyCurrentResponse selectCurrent(
            String ownerId,
            UUID documentFamilyId,
            String idempotencyKey,
            SelectFamilyCurrentRequest request) {
        requireOwner(ownerId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required.");
        }
        try {
            return requiredBody(restTemplate.exchange(
                    storeBaseUrl + "/api/v1/documents/families/{familyId}/current",
                    HttpMethod.PATCH,
                    new HttpEntity<>(
                            request,
                            headers(ownerId, producerToken, idempotencyKey)),
                    DocumentFamilyCurrentResponse.class,
                    documentFamilyId));
        } catch (HttpStatusCodeException exception) {
            throw new DocumentHistoryDownstreamException(exception.getStatusCode());
        }
    }

    private HttpHeaders headers(
            String ownerId, String serviceToken, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, serviceToken);
        headers.set(OWNER_HEADER, ownerId);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
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
                    "Document Store returned an empty history response.");
        }
        return response.getBody();
    }
}
