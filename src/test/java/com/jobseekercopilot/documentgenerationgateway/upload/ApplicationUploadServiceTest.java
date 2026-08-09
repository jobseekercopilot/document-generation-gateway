package com.jobseekercopilot.documentgenerationgateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationUploadConflictException;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationUploadNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.ResourceAccessException;

class ApplicationUploadServiceTest {
    private static final String OWNER = "owner-123";
    private static final String JOB = "canonical-job-1";
    private static final byte[] PDF = "%PDF-1.7\nclean".getBytes(StandardCharsets.UTF_8);

    private final UUID applicationId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID storeOperationId = UUID.randomUUID();
    private final UUID siblingDocumentId = UUID.randomUUID();

    private ApplicationUploadDownstreamClient downstream;
    private ApplicationUploadService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V4__create_application_document_upload_operations.sql"))
                .execute(dataSource);
        downstream = mock(ApplicationUploadDownstreamClient.class);
        service = new ApplicationUploadService(
                new ApplicationUploadOperationRepository(new JdbcTemplate(dataSource)),
                downstream);
    }

    @Test
    void cleanUploadLinksExactVersionAndPreservesSibling() {
        when(downstream.application(OWNER, applicationId))
                .thenReturn(application(4, null, siblingDocumentId));
        when(downstream.upload(any(), eq(PDF), eq("cv.pdf")))
                .thenReturn(storeReady(DocumentKind.CV));
        when(downstream.saveSelections(any(), eq(4L), eq(documentId), eq(siblingDocumentId)))
                .thenReturn(application(5, documentId, siblingDocumentId));

        var response = service.upload(
                OWNER,
                applicationId,
                JOB,
                DocumentKind.CV,
                UploadFormat.PDF,
                "upload-cv-1",
                file(PDF));

        assertThat(response.state()).isEqualTo(ApplicationUploadState.COMPLETED);
        assertThat(response.documentId()).isEqualTo(documentId);
        assertThat(response.applicationVersion()).isEqualTo(5);
        assertThat(response.failureCode()).isNull();
        verify(downstream).saveSelections(any(), eq(4L), eq(documentId), eq(siblingDocumentId));
    }

    @Test
    void exactReplayDoesNotDuplicateStoreOrTrackerWork() {
        when(downstream.application(OWNER, applicationId))
                .thenReturn(application(2, null, null));
        when(downstream.upload(any(), eq(PDF), eq("cv.pdf")))
                .thenReturn(storeReady(DocumentKind.CV));
        when(downstream.saveSelections(any(), eq(2L), eq(documentId), eq(null)))
                .thenReturn(application(3, documentId, null));

        var first = service.upload(
                OWNER, applicationId, JOB, DocumentKind.CV, UploadFormat.PDF,
                "same-upload", file(PDF));
        var replay = service.upload(
                OWNER, applicationId, JOB, DocumentKind.CV, UploadFormat.PDF,
                "same-upload", file(PDF));

        assertThat(replay.operationId()).isEqualTo(first.operationId());
        assertThat(replay.state()).isEqualTo(ApplicationUploadState.COMPLETED);
        verify(downstream, times(1)).upload(any(), eq(PDF), eq("cv.pdf"));
        verify(downstream, times(1)).saveSelections(any(), eq(2L), eq(documentId), eq(null));
    }

    @Test
    void changedPayloadWithSameKeyConflictsBeforeMoreDownstreamWork() {
        when(downstream.application(OWNER, applicationId))
                .thenReturn(application(1, null, null));
        when(downstream.upload(any(), eq(PDF), eq("cv.pdf")))
                .thenReturn(storeReady(DocumentKind.CV));
        when(downstream.saveSelections(any(), eq(1L), eq(documentId), eq(null)))
                .thenReturn(application(2, documentId, null));
        service.upload(
                OWNER, applicationId, JOB, DocumentKind.CV, UploadFormat.PDF,
                "bound-upload", file(PDF));

        byte[] changed = "%PDF-1.7\nchanged".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.upload(
                        OWNER,
                        applicationId,
                        JOB,
                        DocumentKind.CV,
                        UploadFormat.PDF,
                        "bound-upload",
                        file(changed)))
                .isInstanceOf(ApplicationUploadConflictException.class);
        verify(downstream, times(1)).upload(any(), any(), any());
        verify(downstream, times(1)).saveSelections(any(), eq(1L), eq(documentId), eq(null));
    }

    @Test
    void trackerFailureResumesWithoutCreatingAnotherDocumentVersion() {
        when(downstream.application(OWNER, applicationId))
                .thenReturn(application(7, siblingDocumentId, null));
        when(downstream.upload(any(), eq(PDF), eq("cv.pdf")))
                .thenReturn(storeReady(DocumentKind.COVER_LETTER));
        when(downstream.saveSelections(
                        any(), eq(7L), eq(siblingDocumentId), eq(documentId)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(application(8, siblingDocumentId, documentId));

        var interrupted = service.upload(
                OWNER,
                applicationId,
                JOB,
                DocumentKind.COVER_LETTER,
                UploadFormat.PDF,
                "recover-link",
                file(PDF));
        var recovered = service.upload(
                OWNER,
                applicationId,
                JOB,
                DocumentKind.COVER_LETTER,
                UploadFormat.PDF,
                "recover-link",
                file(PDF));

        assertThat(interrupted.state()).isEqualTo(ApplicationUploadState.RECOVERY_REQUIRED);
        assertThat(interrupted.documentId()).isEqualTo(documentId);
        assertThat(recovered.state()).isEqualTo(ApplicationUploadState.COMPLETED);
        verify(downstream, times(1)).upload(any(), eq(PDF), eq("cv.pdf"));
        verify(downstream, times(2)).saveSelections(
                any(), eq(7L), eq(siblingDocumentId), eq(documentId));
    }

    @Test
    void mismatchedApplicationIsDeniedBeforeDocumentStore() {
        when(downstream.application(OWNER, applicationId)).thenReturn(Map.of(
                "id", applicationId,
                "canonicalJobId", "different-job",
                "status", "SAVED",
                "version", 0));

        assertThatThrownBy(() -> service.upload(
                        OWNER,
                        applicationId,
                        JOB,
                        DocumentKind.CV,
                        UploadFormat.PDF,
                        "wrong-context",
                        file(PDF)))
                .isInstanceOf(ApplicationUploadNotFoundException.class);
        verify(downstream, times(0)).upload(any(), any(), any());
    }

    @Test
    void operationStatusIsOwnerScoped() {
        successfulUpload("owner-status");
        UUID operationId = service.upload(
                OWNER, applicationId, JOB, DocumentKind.CV, UploadFormat.PDF,
                "owner-status", file(PDF)).operationId();

        assertThatThrownBy(() -> service.get("other-owner", operationId))
                .isInstanceOf(ApplicationUploadNotFoundException.class);
    }

    @Test
    void mismatchedStoreEvidenceNeverReachesApplicationTrackerLink() {
        when(downstream.application(OWNER, applicationId))
                .thenReturn(application(1, null, null));
        Map<String, Object> mismatched = new java.util.LinkedHashMap<>(
                storeReady(DocumentKind.CV));
        mismatched.put("applicationId", UUID.randomUUID().toString());
        when(downstream.upload(any(), eq(PDF), eq("cv.pdf"))).thenReturn(mismatched);

        var response = service.upload(
                OWNER, applicationId, JOB, DocumentKind.CV, UploadFormat.PDF,
                "mismatched-store", file(PDF));

        assertThat(response.state()).isEqualTo(ApplicationUploadState.RECOVERY_REQUIRED);
        assertThat(response.failureCode()).isEqualTo("DOCUMENT_STORE_EVIDENCE_MISMATCH");
        verify(downstream, never()).saveSelections(any(), any(Long.class), any(), any());
    }

    @Test
    void mismatchedTrackerEvidenceDoesNotCompleteOperation() {
        when(downstream.application(OWNER, applicationId))
                .thenReturn(application(1, null, null));
        when(downstream.upload(any(), eq(PDF), eq("cv.pdf")))
                .thenReturn(storeReady(DocumentKind.CV));
        when(downstream.saveSelections(any(), eq(1L), eq(documentId), eq(null)))
                .thenReturn(Map.of(
                        "id", UUID.randomUUID().toString(),
                        "version", 2,
                        "cvDocumentId", documentId.toString()));

        var response = service.upload(
                OWNER, applicationId, JOB, DocumentKind.CV, UploadFormat.PDF,
                "mismatched-tracker", file(PDF));

        assertThat(response.state()).isEqualTo(ApplicationUploadState.RECOVERY_REQUIRED);
        assertThat(response.failureCode()).isEqualTo("APPLICATION_TRACKER_EVIDENCE_MISMATCH");
    }

    private void successfulUpload(String key) {
        when(downstream.application(OWNER, applicationId))
                .thenReturn(application(1, null, null));
        when(downstream.upload(any(), eq(PDF), eq("cv.pdf")))
                .thenReturn(storeReady(DocumentKind.CV));
        when(downstream.saveSelections(any(), eq(1L), eq(documentId), eq(null)))
                .thenReturn(application(2, documentId, null));
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "cv.pdf", "application/pdf", bytes);
    }

    private Map<String, Object> application(long version, UUID cv, UUID coverLetter) {
        var application = new java.util.LinkedHashMap<String, Object>();
        application.put("id", applicationId.toString());
        application.put("canonicalJobId", JOB);
        application.put("status", "SAVED");
        application.put("version", version);
        application.put("cvDocumentId", cv == null ? null : cv.toString());
        application.put("coverLetterDocumentId", coverLetter == null ? null : coverLetter.toString());
        return application;
    }

    private Map<String, Object> storeReady(DocumentKind documentType) {
        return Map.of(
                "operationId", storeOperationId.toString(),
                "applicationId", applicationId.toString(),
                "jobId", JOB,
                "documentType", documentType.name(),
                "fileType", "PDF",
                "state", "READY",
                "originalSha256", sha256(PDF),
                "originalSize", PDF.length,
                "documentId", documentId.toString());
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
