package com.jobseekercopilot.documentgenerationgateway.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

class DocumentFileDownloadServiceTest {
    @Test
    void proxiesDownloadBytesAndHeadersFromDocumentStore() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID fileId = UUID.randomUUID();
        byte[] body = "docx-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.setContentType(MediaType.APPLICATION_PDF);
        upstreamHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cv.pdf\"");
        upstreamHeaders.setContentLength(body.length);
        upstreamHeaders.set("X-Content-Type-Options", "nosniff");
        upstreamHeaders.setCacheControl("private, no-store, max-age=0");
        upstreamHeaders.setPragma("no-cache");

        when(restTemplate.exchange(
                Mockito.eq("http://document-store-service:8089/api/v1/document-files/{fileId}/download"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(byte[].class),
                Mockito.eq(fileId)))
                .thenReturn(new ResponseEntity<>(body, upstreamHeaders, HttpStatus.OK));

        var service = new DocumentFileDownloadService(
                restTemplate,
                "http://document-store-service:8089",
                "test-only-document-store-reader-token-32-bytes");
        ResponseEntity<byte[]> actual = service.download(fileId, "alice");

        assertEquals(HttpStatus.OK, actual.getStatusCode());
        assertArrayEquals(body, actual.getBody());
        assertEquals(MediaType.APPLICATION_PDF, actual.getHeaders().getContentType());
        assertEquals("attachment; filename=\"cv.pdf\"", actual.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(body.length, actual.getHeaders().getContentLength());
        assertEquals("nosniff", actual.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(
                "private, no-store, max-age=0",
                actual.getHeaders().getCacheControl());
        assertEquals("no-cache", actual.getHeaders().getPragma());

        var request = ArgumentCaptor.forClass(HttpEntity.class);
        Mockito.verify(restTemplate).exchange(
                Mockito.eq("http://document-store-service:8089/api/v1/document-files/{fileId}/download"),
                Mockito.eq(HttpMethod.GET),
                request.capture(),
                Mockito.eq(byte[].class),
                Mockito.eq(fileId));
        assertEquals(
                "test-only-document-store-reader-token-32-bytes",
                request.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals(
                "alice",
                request.getValue().getHeaders().getFirst("X-Document-Owner"));
        assertEquals(
                1,
                request.getValue().getHeaders().get("X-Document-Owner").size());
    }

    @Test
    void exactDownloadBindsDocumentAndArtifactAndPreservesSecurityPolicy() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID documentId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        byte[] body = "historic-docx".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        upstreamHeaders.set(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"cv-v2.docx\"");
        upstreamHeaders.setContentLength(body.length);
        upstreamHeaders.set("X-Content-Type-Options", "nosniff");
        upstreamHeaders.setCacheControl("private, no-store, max-age=0");
        upstreamHeaders.setPragma("no-cache");

        when(restTemplate.exchange(
                Mockito.eq("http://document-store-service:8089/api/v1/documents/{documentId}/artifacts/{artifactId}/download"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(byte[].class),
                Mockito.eq(documentId),
                Mockito.eq(artifactId)))
                .thenReturn(new ResponseEntity<>(body, upstreamHeaders, HttpStatus.OK));

        var service = service(restTemplate);
        ResponseEntity<byte[]> actual = service.downloadExactArtifact(
                documentId, artifactId, "alice");

        assertArrayEquals(body, actual.getBody());
        assertEquals("nosniff", actual.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("private, no-store, max-age=0", actual.getHeaders().getCacheControl());
        assertEquals("no-cache", actual.getHeaders().getPragma());
        verifyExactRequest(restTemplate, documentId, artifactId);
    }

    @Test
    void exactDownloadPreservesSafeErrorPolicyWithoutLeakingDownstreamBody() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        UUID documentId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setCacheControl("private, no-store, max-age=0");
        headers.setPragma("no-cache");
        when(restTemplate.exchange(
                Mockito.eq("http://document-store-service:8089/api/v1/documents/{documentId}/artifacts/{artifactId}/download"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(byte[].class),
                Mockito.eq(documentId),
                Mockito.eq(artifactId)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND,
                        "Not Found",
                        headers,
                        "internal storage URL".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        ResponseEntity<byte[]> actual = service(restTemplate)
                .downloadExactArtifact(documentId, artifactId, "alice");

        assertEquals(HttpStatus.NOT_FOUND, actual.getStatusCode());
        assertEquals(null, actual.getBody());
        assertEquals("nosniff", actual.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("private, no-store, max-age=0", actual.getHeaders().getCacheControl());
        assertEquals("no-cache", actual.getHeaders().getPragma());
    }

    @Test
    void rejectsMissingOwnerBeforeCallingDocumentStore() {
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        var service = new DocumentFileDownloadService(
                restTemplate,
                "http://document-store-service:8089",
                "test-only-document-store-reader-token-32-bytes");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.download(UUID.randomUUID(), " "));

        assertEquals("Authenticated document owner is required.", exception.getMessage());
        verifyNoInteractions(restTemplate);
    }

    private DocumentFileDownloadService service(RestTemplate restTemplate) {
        return new DocumentFileDownloadService(
                restTemplate,
                "http://document-store-service:8089",
                "test-only-document-store-reader-token-32-bytes");
    }

    private void verifyExactRequest(
            RestTemplate restTemplate, UUID documentId, UUID artifactId) {
        var request = ArgumentCaptor.forClass(HttpEntity.class);
        Mockito.verify(restTemplate).exchange(
                Mockito.eq("http://document-store-service:8089/api/v1/documents/{documentId}/artifacts/{artifactId}/download"),
                Mockito.eq(HttpMethod.GET),
                request.capture(),
                Mockito.eq(byte[].class),
                Mockito.eq(documentId),
                Mockito.eq(artifactId));
        assertEquals(
                "test-only-document-store-reader-token-32-bytes",
                request.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals(
                "alice",
                request.getValue().getHeaders().getFirst("X-Document-Owner"));
    }
}
