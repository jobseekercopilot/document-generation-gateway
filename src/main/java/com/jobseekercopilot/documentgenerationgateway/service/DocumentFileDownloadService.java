package com.jobseekercopilot.documentgenerationgateway.service;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
public class DocumentFileDownloadService {
    private final RestTemplate restTemplate;
    private final String documentStoreBaseUrl;

    public DocumentFileDownloadService(RestTemplate restTemplate,
                                       @Value("${services.document-store-service.base-url}") String documentStoreBaseUrl) {
        this.restTemplate = restTemplate;
        this.documentStoreBaseUrl = documentStoreBaseUrl;
    }

    public ResponseEntity<byte[]> download(UUID fileId) {
        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    documentStoreBaseUrl + "/api/v1/document-files/{fileId}/download",
                    HttpMethod.GET,
                    null,
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
