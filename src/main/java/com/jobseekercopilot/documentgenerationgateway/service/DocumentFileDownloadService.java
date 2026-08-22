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
    private static final String CONTENT_TYPE_OPTIONS_HEADER =
            "X-Content-Type-Options";

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
        return downloadFromStore(
                "/api/v1/document-files/{fileId}/download",
                ownerId,
                fileId);
    }

    public ResponseEntity<byte[]> downloadExactArtifact(
            UUID documentId, UUID artifactId, String ownerId) {
        return downloadFromStore(
                "/api/v1/documents/{documentId}/artifacts/{artifactId}/download",
                ownerId,
                documentId,
                artifactId);
    }

    private ResponseEntity<byte[]> downloadFromStore(
            String path, String ownerId, Object... pathVariables) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("Authenticated document owner is required.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, documentStoreReaderToken);
        headers.set(DOCUMENT_OWNER_HEADER, ownerId);
        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    documentStoreBaseUrl + path,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    byte[].class,
                    pathVariables);

            return ResponseEntity.status(upstream.getStatusCode())
                    .headers(proxiedHeaders(upstream.getHeaders()))
                    .body(upstream.getBody());
        } catch (HttpStatusCodeException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .headers(proxiedErrorHeaders(exception.getResponseHeaders()))
                    .build();
        }
    }

    private HttpHeaders proxiedHeaders(HttpHeaders source) {
        HttpHeaders target = new HttpHeaders();
        if (source != null) {
            copyHeader(source, target, HttpHeaders.CONTENT_TYPE);
            copyHeader(source, target, HttpHeaders.CONTENT_DISPOSITION);
            copyHeader(source, target, HttpHeaders.CONTENT_LENGTH);
            copyHeader(source, target, CONTENT_TYPE_OPTIONS_HEADER);
            copyHeader(source, target, HttpHeaders.CACHE_CONTROL);
            copyHeader(source, target, HttpHeaders.PRAGMA);
        }
        return target;
    }

    private HttpHeaders proxiedErrorHeaders(HttpHeaders source) {
        HttpHeaders target = new HttpHeaders();
        if (source != null) {
            copyHeader(source, target, CONTENT_TYPE_OPTIONS_HEADER);
            copyHeader(source, target, HttpHeaders.CACHE_CONTROL);
            copyHeader(source, target, HttpHeaders.PRAGMA);
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
