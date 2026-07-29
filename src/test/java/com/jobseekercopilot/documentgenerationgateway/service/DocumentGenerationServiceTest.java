package com.jobseekercopilot.documentgenerationgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportFileItem;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportLatestFiles;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.generated.documentexportservice.api.DocumentExportsApi;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportItem;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportRequest;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportResponse;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

class DocumentGenerationServiceTest {
    private RestTemplate uploadRestTemplate;

    @Test
    void uploadReplacementAllowsDocumentsGeneratedApplications() {
        var service = uploadServiceWithStatus("DOCUMENTS_GENERATED");
        UUID generatedDocumentId = UUID.randomUUID();
        UUID uploadedFileId = UUID.randomUUID();
        var uploadResponse = new ExportUploadResponse(
                generatedDocumentId,
                new ExportFileItem(uploadedFileId, "DOCX", "cv.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null),
                java.util.List.of(),
                new ExportLatestFiles(new ExportFileItem(uploadedFileId, "DOCX", "cv.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null), null),
                "Uploaded");
        var file = new MockMultipartFile("file", "cv.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "content".getBytes());

        when(uploadRestTemplate.postForObject(Mockito.eq("http://export/api/v1/document-exports/documents/{generatedDocumentId}/upload"),
                Mockito.any(), Mockito.eq(ExportUploadResponse.class), Mockito.eq(generatedDocumentId)))
                .thenReturn(uploadResponse);

        var actual = service.uploadReplacement(
                generatedDocumentId,
                "user-123",
                file,
                DocumentKind.CV,
                UploadFormat.DOCX);

        assertEquals(uploadedFileId, actual.uploadedFile().fileId());
        var trackerRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(uploadRestTemplate).exchange(
                Mockito.eq("http://tracker/api/v1/applications/document/{documentId}"),
                Mockito.eq(HttpMethod.GET),
                trackerRequest.capture(),
                Mockito.eq(Map.class),
                Mockito.eq(generatedDocumentId.toString()));
        assertEquals(
                "test-only-application-producer-token-32-bytes",
                trackerRequest.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals(
                "user-123",
                trackerRequest.getValue().getHeaders().getFirst("X-Application-Owner"));

        var exportRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(uploadRestTemplate).postForObject(
                Mockito.eq("http://export/api/v1/document-exports/documents/{generatedDocumentId}/upload"),
                exportRequest.capture(),
                Mockito.eq(ExportUploadResponse.class),
                Mockito.eq(generatedDocumentId));
        assertEquals(
                "test-only-document-export-service-token-32-bytes",
                exportRequest.getValue().getHeaders().getFirst("X-Service-Token"));
        assertEquals(
                1,
                exportRequest.getValue().getHeaders().get("X-Service-Token").size());
        assertEquals(
                "user-123",
                exportRequest.getValue().getHeaders().getFirst("X-Document-Owner"));
        assertEquals(
                1,
                exportRequest.getValue().getHeaders().get("X-Document-Owner").size());
    }

    @Test
    void uploadReplacementRejectsAppliedApplications() {
        var service = uploadServiceWithStatus("APPLIED");
        var file = new MockMultipartFile("file", "cv.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "content".getBytes());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.uploadReplacement(
                        UUID.randomUUID(),
                        "user-123",
                        file,
                        DocumentKind.CV,
                        UploadFormat.DOCX));

        assertEquals("Documents cannot be replaced after the application has been marked as applied.",
                exception.getMessage());
    }

    @Test
    void uploadReplacementRejectsInterviewApplications() {
        var service = uploadServiceWithStatus("INTERVIEW");
        var file = new MockMultipartFile("file", "cv.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "content".getBytes());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.uploadReplacement(
                        UUID.randomUUID(),
                        "user-123",
                        file,
                        DocumentKind.CV,
                        UploadFormat.DOCX));

        assertEquals("Documents cannot be replaced after the application has been marked as applied.",
                exception.getMessage());
    }

    @Test
    void uploadReplacementFailsBeforeTrackerAccessWithoutAnAuthenticatedOwner() {
        var service = uploadServiceWithStatus("DOCUMENTS_GENERATED");
        var file = new MockMultipartFile("file", "cv.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "content".getBytes());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.uploadReplacement(
                        UUID.randomUUID(),
                        " ",
                        file,
                        DocumentKind.CV,
                        UploadFormat.DOCX));

        assertEquals("Authenticated application owner is required.", exception.getMessage());
    }

    @Test
    void replacementBindsEveryDirectStoreCallToTheValidatedOwnerAndLeastPrivilegeRole()
            throws Exception {
        var profiles = Mockito.mock(UserProfilesApi.class);
        var exporter = Mockito.mock(DocumentExportsApi.class);
        var restTemplate = Mockito.mock(RestTemplate.class);
        UUID applicationId = UUID.randomUUID();
        UUID currentDocumentId = UUID.randomUUID();
        UUID documentFamilyId = UUID.randomUUID();
        UUID newDocumentId = UUID.randomUUID();
        UUID uploadedFileId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID coverLetterId = UUID.randomUUID();

        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "userId", "alice",
                        "jobId", "job-1",
                        "jobTitle", "Developer",
                        "status", "DOCUMENTS_GENERATED",
                        "cvDocumentId", currentDocumentId.toString())));
        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}/document-replacements"),
                Mockito.eq(HttpMethod.POST),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId)))
                .thenReturn(ResponseEntity.accepted().body(Map.of(
                        "operationId", workflowId.toString(),
                        "applicationId", applicationId.toString(),
                        "documentType", "CV",
                        "sourceDocumentId", currentDocumentId.toString(),
                        "operationStatus", "PENDING",
                        "retryable", true,
                        "cvDocumentId", currentDocumentId.toString(),
                        "coverLetterDocumentId", coverLetterId.toString())));
        when(restTemplate.exchange(
                Mockito.eq("http://store/api/v1/documents/{documentId}"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(currentDocumentId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "title", "Developer CV",
                        "documentFamilyId", documentFamilyId.toString())));
        when(restTemplate.exchange(
                Mockito.eq("http://store/api/v1/documents"),
                Mockito.eq(HttpMethod.POST),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", newDocumentId.toString(),
                        "version", 2)));
        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}/document-replacements/{operationId}/replacement-document"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId),
                Mockito.eq(workflowId)))
                .thenReturn(ResponseEntity.accepted().body(Map.of(
                        "operationId", workflowId.toString(),
                        "operationStatus", "RUNNING")));
        when(restTemplate.postForObject(
                Mockito.eq("http://export/api/v1/document-exports/documents/{generatedDocumentId}/upload"),
                Mockito.any(),
                Mockito.eq(ExportUploadResponse.class),
                Mockito.eq(newDocumentId)))
                .thenReturn(new ExportUploadResponse(
                        newDocumentId,
                        new ExportFileItem(
                                uploadedFileId,
                                "DOCX",
                                "cv.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                null),
                        java.util.List.of(),
                        new ExportLatestFiles(null, null),
                        "Uploaded"));
        when(restTemplate.exchange(
                Mockito.eq("http://store/api/v1/documents/{documentId}/approve"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(newDocumentId)))
                .thenReturn(ResponseEntity.ok(Map.of()));
        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}/document-replacements/{operationId}/complete"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId),
                Mockito.eq(workflowId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "operationId", workflowId.toString(),
                        "operationStatus", "COMPLETED",
                        "retryable", false,
                        "cvDocumentId", newDocumentId.toString(),
                        "coverLetterDocumentId", coverLetterId.toString())));

        var service = new DocumentGenerationService(
                profiles,
                exporter,
                new ObjectMapper(),
                restTemplate,
                "http://cv",
                "http://auth",
                "http://export",
                "http://store",
                "http://tracker",
                "test-only-authentication-service-token-32-bytes",
                "test-only-application-producer-token-32-bytes",
                "test-only-cv-cover-letter-service-token-32-bytes",
                "test-only-document-export-service-token-32-bytes",
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes",
                "test-only-payment-service-token-0000000000001");
        var file = new MockMultipartFile(
                "file",
                "cv.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                validDocx());

        var result = service.replaceApplicationDocument(
                applicationId,
                "alice",
                file,
                DocumentKind.CV);

        assertEquals(newDocumentId, result.generatedDocumentId());
        assertEquals(workflowId, result.operationId());
        assertEquals("COMPLETED", result.operationStatus());

        var readRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                Mockito.eq("http://store/api/v1/documents/{documentId}"),
                Mockito.eq(HttpMethod.GET),
                readRequest.capture(),
                Mockito.eq(Map.class),
                Mockito.eq(currentDocumentId));
        assertStoreIdentity(
                readRequest.getValue(),
                "test-only-document-store-reader-token-32-bytes");

        var createRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                Mockito.eq("http://store/api/v1/documents"),
                Mockito.eq(HttpMethod.POST),
                createRequest.capture(),
                Mockito.eq(Map.class));
        assertStoreIdentity(
                createRequest.getValue(),
                "test-only-document-store-producer-token-32-bytes");
        assertEquals("alice", ((Map<?, ?>) createRequest.getValue().getBody()).get("userId"));
        assertEquals(
                documentFamilyId.toString(),
                ((Map<?, ?>) createRequest.getValue().getBody())
                        .get("documentFamilyId"));
        assertEquals(
                workflowId + ":document",
                createRequest.getValue().getHeaders()
                        .getFirst("Idempotency-Key"));

        var approveRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                Mockito.eq("http://store/api/v1/documents/{documentId}/approve"),
                Mockito.eq(HttpMethod.PATCH),
                approveRequest.capture(),
                Mockito.eq(Map.class),
                Mockito.eq(newDocumentId));
        assertStoreIdentity(
                approveRequest.getValue(),
                "test-only-document-store-producer-token-32-bytes");

        var exportRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(
                Mockito.eq("http://export/api/v1/document-exports/documents/{generatedDocumentId}/upload"),
                exportRequest.capture(),
                Mockito.eq(ExportUploadResponse.class),
                Mockito.eq(newDocumentId));
        assertEquals(
                workflowId.toString(),
                exportRequest.getValue().getHeaders()
                        .getFirst("Idempotency-Key"));
    }

    @Test
    void replacementDependencyFailureReturnsDurableRecoveryOutcome()
            throws Exception {
        var profiles = Mockito.mock(UserProfilesApi.class);
        var exporter = Mockito.mock(DocumentExportsApi.class);
        var restTemplate = Mockito.mock(RestTemplate.class);
        UUID applicationId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID replacementId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "userId", "alice",
                        "jobId", "job-1",
                        "jobTitle", "Developer",
                        "status", "DOCUMENTS_GENERATED",
                        "cvDocumentId", sourceDocumentId.toString())));
        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}/document-replacements"),
                Mockito.eq(HttpMethod.POST),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId)))
                .thenReturn(ResponseEntity.accepted().body(Map.of(
                        "operationId", operationId.toString(),
                        "sourceDocumentId", sourceDocumentId.toString(),
                        "operationStatus", "PENDING",
                        "retryable", true)));
        when(restTemplate.exchange(
                Mockito.eq("http://store/api/v1/documents/{documentId}"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(sourceDocumentId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "title", "Developer CV",
                        "documentFamilyId", familyId.toString())));
        when(restTemplate.exchange(
                Mockito.eq("http://store/api/v1/documents"),
                Mockito.eq(HttpMethod.POST),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", replacementId.toString(),
                        "version", 2)));
        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}/document-replacements/{operationId}/replacement-document"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId),
                Mockito.eq(operationId)))
                .thenReturn(ResponseEntity.accepted().body(Map.of(
                        "operationId", operationId.toString(),
                        "operationStatus", "RUNNING")));
        when(restTemplate.postForObject(
                Mockito.eq("http://export/api/v1/document-exports/documents/{generatedDocumentId}/upload"),
                Mockito.any(),
                Mockito.eq(ExportUploadResponse.class),
                Mockito.eq(replacementId)))
                .thenThrow(new RestClientException("export unavailable"));
        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}/document-replacements/{operationId}/recovery-required"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId),
                Mockito.eq(operationId)))
                .thenReturn(ResponseEntity.accepted().body(Map.of(
                        "operationId", operationId.toString(),
                        "operationStatus", "RECOVERY_REQUIRED",
                        "retryable", true,
                        "recoveryCode", "REPLACEMENT_STEP_FAILED",
                        "cvDocumentId", sourceDocumentId.toString())));

        var service = new DocumentGenerationService(
                profiles,
                exporter,
                new ObjectMapper(),
                restTemplate,
                "http://cv",
                "http://auth",
                "http://export",
                "http://store",
                "http://tracker",
                "test-only-authentication-service-token-32-bytes",
                "test-only-application-producer-token-32-bytes",
                "test-only-cv-cover-letter-service-token-32-bytes",
                "test-only-document-export-service-token-32-bytes",
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes",
                "test-only-payment-service-token-0000000000001");
        var file = new MockMultipartFile(
                "file",
                "cv.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                validDocx());

        var result = service.replaceApplicationDocument(
                applicationId,
                "alice",
                file,
                DocumentKind.CV);

        assertEquals(operationId, result.operationId());
        assertEquals("RECOVERY_REQUIRED", result.operationStatus());
        assertEquals(true, result.retryable());
        assertEquals("REPLACEMENT_STEP_FAILED", result.recoveryCode());
        assertEquals(sourceDocumentId.toString(), result.cvDocumentId());
        verify(restTemplate, never()).exchange(
                Mockito.eq("http://store/api/v1/documents/{documentId}/approve"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(replacementId));
    }

    private DocumentExportResponse exportResponse(UUID docxFileId, UUID pdfFileId, String docxFileName, String pdfFileName) {
        return new DocumentExportResponse()
                .addExportsItem(new DocumentExportItem()
                        .fileId(docxFileId)
                        .format(DocumentExportItem.FormatEnum.DOCX)
                        .fileName(docxFileName))
                .addExportsItem(new DocumentExportItem()
                        .fileId(pdfFileId)
                        .format(DocumentExportItem.FormatEnum.PDF)
                        .fileName(pdfFileName));
    }

    private DocumentGenerationService uploadServiceWithStatus(String status) {
        var profiles = Mockito.mock(UserProfilesApi.class);
        var exporter = Mockito.mock(DocumentExportsApi.class);
        uploadRestTemplate = Mockito.mock(RestTemplate.class);
        when(uploadRestTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/document/{documentId}"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.anyString()))
                .thenReturn(ResponseEntity.ok(java.util.Map.of("status", status)));
        return new DocumentGenerationService(profiles, exporter, new ObjectMapper(), uploadRestTemplate,
                "http://cv", "http://auth", "http://export", "http://store", "http://tracker",
                "test-only-authentication-service-token-32-bytes",
                "test-only-application-producer-token-32-bytes",
                "test-only-cv-cover-letter-service-token-32-bytes",
                "test-only-document-export-service-token-32-bytes",
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes",
                "test-only-payment-service-token-0000000000001");
    }

    private void assertStoreIdentity(HttpEntity<?> request, String expectedToken) {
        assertEquals(expectedToken, request.getHeaders().getFirst("X-Service-Token"));
        assertEquals("alice", request.getHeaders().getFirst("X-Document-Owner"));
        assertEquals(1, request.getHeaders().get("X-Document-Owner").size());
    }

    private byte[] validDocx() throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels"
                        ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml"
                        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                        Target="word/document.xml"/>
                    </Relationships>
                    """.getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Replacement CV</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.getBytes());
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        }
    }
}
