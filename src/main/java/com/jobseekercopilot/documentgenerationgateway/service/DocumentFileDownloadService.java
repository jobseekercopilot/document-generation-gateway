package com.jobseekercopilot.documentgenerationgateway.service;

import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import java.util.List;
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
public class DocumentFileDownloadService {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String DOCUMENT_OWNER_HEADER = "X-Document-Owner";

    private final RestTemplate restTemplate;
    private final String documentStoreBaseUrl;
    private final String documentStoreReaderToken;

    @Autowired
    public DocumentFileDownloadService(
            RestTemplate restTemplate,
            @Value("${services.document-store-service.base-url}") String documentStoreBaseUrl,
            DownstreamServiceCredentials credentials) {
        this(restTemplate, documentStoreBaseUrl, credentials.documentStoreReaderToken());
    }

    DocumentFileDownloadService(
            RestTemplate restTemplate,
            String documentStoreBaseUrl,
            String documentStoreReaderToken) {
        this.restTemplate = restTemplate;
        this.documentStoreBaseUrl = documentStoreBaseUrl;
        this.documentStoreReaderToken = documentStoreReaderToken;
    }

    public ResponseEntity<byte[]> download(UUID fileId, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Authenticated document owner is required.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, documentStoreReaderToken);
        headers.set(DOCUMENT_OWNER_HEADER, ownerId);
        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    documentStoreBaseUrl + "/api/v1/document-files/{fileId}/download",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    byte[].class,
                    fileId);

            return ResponseEntity.status(upstream.getStatusCode())
                    .headers(proxiedHeaders(upstream.getHeaders()))
                    .body(upstream.getBody());
        } catch (HttpStatusCodeException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .headers(proxiedHeaders(exception.getResponseHeaders()))
                    .body(exception.getResponseBodyAsByteArray());
        }
    }

    private HttpHeaders proxiedHeaders(HttpHeaders source) {
        HttpHeaders target = new HttpHeaders();
        if (source != null) {
            copyHeader(source, target, HttpHeaders.CONTENT_TYPE);
            copyHeader(source, target, HttpHeaders.CONTENT_DISPOSITION);
            copyHeader(source, target, HttpHeaders.CONTENT_LENGTH);
        }
        return target;
    }

    private void copyHeader(HttpHeaders source, HttpHeaders target, String headerName) {
        List<String> values = source.get(headerName);
        if (values != null && !values.isEmpty()) {
            target.put(headerName, values);
        }
    }
}
