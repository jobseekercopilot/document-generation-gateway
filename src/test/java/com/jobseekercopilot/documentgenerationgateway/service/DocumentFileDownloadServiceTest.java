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
}
