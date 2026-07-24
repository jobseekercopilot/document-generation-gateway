package com.jobseekercopilot.documentgenerationgateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportFileItem;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportLatestFiles;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.generated.cvcoverletterservice.model.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.generated.cvcoverletterservice.model.Job;
import com.jobseekercopilot.generated.documentexportservice.api.DocumentExportsApi;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportItem;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportRequest;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportResponse;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import com.jobseekercopilot.generated.userprofileservice.model.UserProfile;
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

class DocumentGenerationServiceTest {
    private RestTemplate uploadRestTemplate;

    @Test
    void getsProfileGeneratesDocumentsExportsFilesAndReturnsGatewayDownloadUrls() {
        var profiles = Mockito.mock(UserProfilesApi.class);
        var exporter = Mockito.mock(DocumentExportsApi.class);
        var restTemplate = Mockito.mock(RestTemplate.class);
        var profile = new UserProfile();
        var job = new Job().id("job-123").title("Developer").company("Example").description("Build things");
        UUID cvDocumentId = UUID.randomUUID();
        UUID coverLetterDocumentId = UUID.randomUUID();
        UUID cvDocxFileId = UUID.randomUUID();
        UUID cvPdfFileId = UUID.randomUUID();
        UUID letterDocxFileId = UUID.randomUUID();
        UUID letterPdfFileId = UUID.randomUUID();
        var generated = new GenerateCvCoverLetterResponse()
                .applicationId("application-1")
                .cvDocumentId(cvDocumentId.toString())
                .coverLetterDocumentId(coverLetterDocumentId.toString());
        when(profiles.getMyProfile()).thenReturn(profile);
        when(restTemplate.exchange(Mockito.eq("http://auth/api/auth/me"), Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(), Mockito.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(java.util.Map.of("name", "Alex Candidate", "email", "alex@example.com")));
        when(restTemplate.postForObject(Mockito.eq("http://cv/api/v1/cv-cover-letter/generate"),
                Mockito.any(), Mockito.eq(GenerateCvCoverLetterResponse.class))).thenReturn(generated);
        when(exporter.exportDocument(
                Mockito.eq("user-123"),
                Mockito.eq(cvDocumentId),
                Mockito.any()))
                .thenReturn(exportResponse(cvDocxFileId, cvPdfFileId, "cv.docx", "cv.pdf"));
        when(exporter.exportDocument(
                Mockito.eq("user-123"),
                Mockito.eq(coverLetterDocumentId),
                Mockito.any()))
                .thenReturn(exportResponse(letterDocxFileId, letterPdfFileId, "letter.docx", "letter.pdf"));

        var service = new DocumentGenerationService(profiles, exporter, new ObjectMapper(), restTemplate,
                "http://cv", "http://auth", "http://export", "http://store", "http://tracker",
                "test-only-authentication-service-token-32-bytes",
                "test-only-application-producer-token-32-bytes",
                "test-only-document-export-service-token-32-bytes",
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes");
        var actual = service.generate("user-123", "Bearer token", job);

        assertEquals("application-1", actual.applicationId());
        assertEquals(cvDocumentId.toString(), actual.cvDocumentId());
        assertEquals(coverLetterDocumentId.toString(), actual.coverLetterDocumentId());
        assertEquals(cvDocxFileId, actual.downloads().cv().docx().fileId());
        assertEquals("/api/v1/document-generation/files/" + cvDocxFileId + "/download",
                actual.downloads().cv().docx().downloadUrl());
        assertEquals("cv.docx", actual.downloads().cv().docx().fileName());
        assertEquals(cvPdfFileId, actual.downloads().cv().pdf().fileId());
        assertEquals(letterDocxFileId, actual.downloads().coverLetter().docx().fileId());
        assertEquals(letterPdfFileId, actual.downloads().coverLetter().pdf().fileId());

        var generationRequest = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate).postForObject(Mockito.eq("http://cv/api/v1/cv-cover-letter/generate"),
                generationRequest.capture(), Mockito.eq(GenerateCvCoverLetterResponse.class));
        var entity = (HttpEntity<?>) generationRequest.getValue();
        assertEquals("user-123", entity.getHeaders().getFirst("X-User-Id"));
        var body = (java.util.Map<?, ?>) entity.getBody();
        var profileBody = (java.util.Map<?, ?>) body.get("userProfile");
        assertEquals("Alex Candidate", profileBody.get("fullName"));
        assertEquals("alex@example.com", profileBody.get("email"));

        var authenticationRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                Mockito.eq("http://auth/api/auth/me"),
                Mockito.eq(HttpMethod.GET),
                authenticationRequest.capture(),
                Mockito.eq(Map.class));
        assertEquals(
                "Bearer token",
                authenticationRequest.getValue().getHeaders().getFirst("Authorization"));
        assertEquals(
                "test-only-authentication-service-token-32-bytes",
                authenticationRequest.getValue().getHeaders().getFirst("X-Service-Token"));

        var exportRequest = ArgumentCaptor.forClass(DocumentExportRequest.class);
        verify(exporter).exportDocument(
                Mockito.eq("user-123"),
                Mockito.eq(cvDocumentId),
                exportRequest.capture());
        assertEquals(
                java.util.List.of(DocumentExportRequest.FormatsEnum.DOCX, DocumentExportRequest.FormatsEnum.PDF),
                exportRequest.getValue().getFormats());
    }

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
        UUID newDocumentId = UUID.randomUUID();
        UUID uploadedFileId = UUID.randomUUID();

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
                Mockito.eq("http://store/api/v1/documents/{documentId}"),
                Mockito.eq(HttpMethod.GET),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(currentDocumentId.toString())))
                .thenReturn(ResponseEntity.ok(Map.of("title", "Developer CV")));
        when(restTemplate.exchange(
                Mockito.eq("http://store/api/v1/documents"),
                Mockito.eq(HttpMethod.POST),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "id", newDocumentId.toString(),
                        "version", 2)));
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
                Mockito.eq("http://store/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId.toString()),
                Mockito.eq("CV"),
                Mockito.eq(newDocumentId)))
                .thenReturn(ResponseEntity.ok(Map.of()));
        when(restTemplate.exchange(
                Mockito.eq("http://tracker/api/v1/applications/{applicationId}/document-reference"),
                Mockito.eq(HttpMethod.PATCH),
                Mockito.<HttpEntity<?>>any(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "cvDocumentId", newDocumentId.toString(),
                        "coverLetterDocumentId", UUID.randomUUID().toString())));

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
                "test-only-document-export-service-token-32-bytes",
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes");
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

        var readRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                Mockito.eq("http://store/api/v1/documents/{documentId}"),
                Mockito.eq(HttpMethod.GET),
                readRequest.capture(),
                Mockito.eq(Map.class),
                Mockito.eq(currentDocumentId.toString()));
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

        var activateRequest = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                Mockito.eq("http://store/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}"),
                Mockito.eq(HttpMethod.PATCH),
                activateRequest.capture(),
                Mockito.eq(Map.class),
                Mockito.eq(applicationId.toString()),
                Mockito.eq("CV"),
                Mockito.eq(newDocumentId));
        assertStoreIdentity(
                activateRequest.getValue(),
                "test-only-document-store-producer-token-32-bytes");
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
                "test-only-document-export-service-token-32-bytes",
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes");
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
