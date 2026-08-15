package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentgenerationgateway.dto.ApproveGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentPurpose;
import com.jobseekercopilot.documentgenerationgateway.dto.EvidenceSection;
import com.jobseekercopilot.documentgenerationgateway.dto.GenerationOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.GenerationOutputResultResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.GenerationRecoverySummaryResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.StartGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationConflictException;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
class DurableGenerationServiceTest {
    private static final String OWNER = "candidate-123";
    private static final String AUTHORIZATION = "Bearer validated-token";
    private static final UUID SAVED_JOB_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RESERVATION_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID COVER_LETTER_RESERVATION_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CV_DOCUMENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID COVER_LETTER_DOCUMENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID APPLICATION_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID CV_EVIDENCE_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID COVER_LETTER_EVIDENCE_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000002");

    @Autowired private DurableGenerationService service;
    @Autowired private GenerationOperationRepository repository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OperationDeadlineGuard deadlineGuard;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private GenerationDownstreamClient downstream;
    @MockBean private GenerationWorkScheduler workScheduler;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM generation_operations");
        reset(downstream, workScheduler);
        doAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return true;
                })
                .when(workScheduler)
                .submit(any(), anyString(), any(Runnable.class));
        successfulDownstream();
    }

    @Test
    void rejectsLeaseThatCannotCoverTheDownstreamReadTimeout() {
        assertThrows(
                IllegalStateException.class,
                () -> new DurableGenerationService(
                        repository,
                        downstream,
                        objectMapper,
                        deadlineGuard,
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30)));
    }

    @Test
    void completesOneReplaySafeOperationThenApprovesExactDrafts() {
        var first = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "generate-job-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                first.state());
        assertEquals(CV_DOCUMENT_ID, first.cvDocumentId());
        assertEquals(
                COVER_LETTER_DOCUMENT_ID,
                first.coverLetterDocumentId());
        assertTrue(first.replaySafe());
        assertFalse(first.manualActionRequired());
        Map<?, ?> persistedLegacyRequest = (Map<?, ?>) repository
                .findByOwnerAndId(first.operationId(), OWNER)
                .orElseThrow()
                .data()
                .get("evidenceSelectionRequest");
        assertFalse(persistedLegacyRequest.containsKey("outputs"));

        var replay = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "generate-job-1",
                selectionRequest());
        assertEquals(first.operationId(), replay.operationId());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, times(1))
                .savedJob(SAVED_JOB_ID, AUTHORIZATION);
        verify(downstream, times(1)).profile();

        assertThrows(
                GenerationConflictException.class,
                () -> approveAndAwait(
                        OWNER,
                        first.operationId(),
                        new ApproveGenerationRequest(
                                UUID.randomUUID(),
                                COVER_LETTER_DOCUMENT_ID)));

        var completed = approveAndAwait(
                OWNER,
                first.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));
        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        assertEquals(APPLICATION_ID, completed.applicationId());
        assertTrue(completed.downloads().containsKey("cv"));
        assertTrue(completed.downloads().containsKey("coverLetter"));

        ArgumentCaptor<Map> application =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).createApplication(
                org.mockito.ArgumentMatchers.eq(OWNER),
                org.mockito.ArgumentMatchers.eq(
                        first.operationId() + ":application"),
                application.capture());
        assertEquals(
                CV_DOCUMENT_ID,
                application.getValue().get("cvDocumentId"));
        assertEquals(
                COVER_LETTER_DOCUMENT_ID,
                application.getValue().get(
                        "coverLetterDocumentId"));
        assertEquals(
                "DOCUMENTS_GENERATED",
                application.getValue().get("initialStatus"));
        verify(downstream).commit(
                OWNER, RESERVATION_ID, 600);
        verify(downstream, never()).release(
                anyString(), any(), anyString());

        assertThrows(
                GenerationNotFoundException.class,
                () -> service.get(
                        "different-owner", first.operationId()));
    }

    @Test
    void generatesAndChargesOnlyTheExplicitlySelectedCv() throws Exception {
        stubSavedApplicationForSelectiveApproval();

        StartGenerationRequest request = new StartGenerationRequest(
                Set.of(DocumentPurpose.CV),
                List.of(new DocumentEvidenceSelection(
                        DocumentPurpose.CV,
                        List.of(CV_EVIDENCE_ID),
                        List.of(EvidenceSection.PROJECT))));

        GenerationOperationResponse waiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "select-cv-only",
                request);

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                waiting.state());
        assertEquals(Set.of(DocumentPurpose.CV), waiting.requestedOutputs());
        assertEquals(CV_DOCUMENT_ID, waiting.cvDocumentId());
        assertNull(waiting.coverLetterDocumentId());
        assertEquals(
                "STORED",
                waiting.outputResults().get("CV").status());
        assertFalse(waiting.outputResults().containsKey("COVER_LETTER"));
        assertFalse(waiting.outputResults().toString().contains("CV content"));
        GenerationRecoverySummaryResponse recovery = waiting
                .outputResults().get("CV").recoverySummary();
        assertEquals("LLM", recovery.generationSource());
        assertEquals("APPLIED", recovery.structuralRepairStatus());
        assertEquals(1, recovery.duplicateItemsRemoved());
        assertEquals(2, recovery.providerAttemptCount());
        assertEquals(1, recovery.automaticRetryCount());
        assertTrue(recovery.retried());
        assertEquals("RATE_LIMITED", recovery.retryReason());
        assertEquals("COMMITTED", recovery.billingStatus());
        assertTrue(recovery.charged());
        assertFalse(recovery.released());
        String publicJson = objectMapper.writeValueAsString(
                waiting.outputResults());
        assertFalse(publicJson.contains("CV content"));
        assertFalse(publicJson.contains("generationMetadata"));
        assertFalse(publicJson.contains("claimLedger"));
        assertFalse(publicJson.contains("evidenceIds"));
        Map<?, ?> persistedSelectiveRequest = (Map<?, ?>) repository
                .findByOwnerAndId(waiting.operationId(), OWNER)
                .orElseThrow()
                .data()
                .get("evidenceSelectionRequest");
        assertEquals(List.of("CV"), persistedSelectiveRequest.get("outputs"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(downstream);
        ArgumentCaptor<Map> savedApplication =
                ArgumentCaptor.forClass(Map.class);
        order.verify(downstream).createApplication(
                eq(OWNER), anyString(), savedApplication.capture());
        assertEquals("SAVED", savedApplication.getValue().get("initialStatus"));
        assertEquals("EXTERNAL", savedApplication.getValue().get("provenance"));
        assertFalse(savedApplication.getValue().containsKey("cvDocumentId"));
        assertFalse(savedApplication.getValue()
                .containsKey("coverLetterDocumentId"));
        ArgumentCaptor<Map> selectedEstimateRequest =
                ArgumentCaptor.forClass(Map.class);
        order.verify(downstream).estimateSelected(
                eq(OWNER), eq(DocumentPurpose.CV),
                selectedEstimateRequest.capture());
        order.verify(downstream).reserveSelected(
                eq(OWNER), any(), eq(DocumentPurpose.CV), eq(300L),
                eq(false));
        ArgumentCaptor<Map> selectedGenerationRequest =
                ArgumentCaptor.forClass(Map.class);
        order.verify(downstream).generateSelected(
                eq(OWNER), any(), eq(DocumentPurpose.CV),
                selectedGenerationRequest.capture());
        for (Map<?, ?> providerRequest : List.of(
                selectedEstimateRequest.getValue(),
                selectedGenerationRequest.getValue())) {
            Map<?, ?> providerProfile = (Map<?, ?>) providerRequest
                    .get("profile");
            assertFalse(providerProfile.containsKey(
                    "professionalContact"));
            assertFalse(providerRequest.toString().contains("7946"));
            assertFalse(providerRequest.toString().contains("github.com"));
        }
        ArgumentCaptor<Map> storedDocument =
                ArgumentCaptor.forClass(Map.class);
        order.verify(downstream).createDocument(
                eq(OWNER), anyString(), storedDocument.capture());
        order.verify(downstream).commit(
                eq(OWNER), eq(RESERVATION_ID), eq(300L));
        assertEquals(
                APPLICATION_ID,
                storedDocument.getValue().get("applicationId"));
        verify(downstream, never()).estimateSelected(
                anyString(), eq(DocumentPurpose.COVER_LETTER), anyMap());
        verify(downstream, never()).reserveSelected(
                anyString(), any(), eq(DocumentPurpose.COVER_LETTER),
                anyLong(), anyBoolean());
        verify(downstream, never()).generateSelected(
                anyString(), any(), eq(DocumentPurpose.COVER_LETTER), anyMap());
        verify(downstream, never()).estimate(anyString(), anyMap());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(anyString(), any(), anyMap());

        GenerationOperationResponse completed = approveAndAwait(
                OWNER,
                waiting.operationId(),
                new ApproveGenerationRequest(CV_DOCUMENT_ID, null));
        assertEquals(GenerationOperationState.COMPLETED, completed.state());
        ArgumentCaptor<Map> selectedExportContact =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).exportDocument(
                eq(OWNER), eq(CV_DOCUMENT_ID), anyString(),
                selectedExportContact.capture());
        assertEquals(
                "+44 20 7946 0958",
                selectedExportContact.getValue().get("phone"));
        assertEquals(
                "https://github.com/example",
                ((Map<?, ?>) ((List<?>) selectedExportContact.getValue()
                        .get("links")).get(0)).get("url"));
        verify(downstream).updateApplicationDocumentSelections(
                eq(OWNER),
                eq(APPLICATION_ID),
                eq(waiting.operationId()
                        + ":application-document-selections"),
                eq(1L),
                eq(CV_DOCUMENT_ID),
                org.mockito.ArgumentMatchers.isNull());
        verify(downstream, never()).updateApplicationStatus(
                anyString(), any(), anyString(), anyLong());

        startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "select-cv-only",
                request);
        verify(downstream, times(1)).generateSelected(
                eq(OWNER), any(), eq(DocumentPurpose.CV), anyMap());
        verify(downstream, times(1)).commit(
                eq(OWNER), any(), eq(300L));
    }

    @Test
    void aLaterDocumentForTheSameSavedJobIsRecordedAsAChargedRegeneration() {
        stubSavedApplicationForSelectiveApproval();

        GenerationOperationResponse first = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "initial-cv-document",
                cvOnlyRequest());
        assertEquals(false, first.outputResults().get("CV").regeneration());
        GenerationOperationResponse completed = approveAndAwait(
                OWNER,
                first.operationId(),
                new ApproveGenerationRequest(CV_DOCUMENT_ID, null));
        assertEquals(GenerationOperationState.COMPLETED, completed.state());

        GenerationOperationResponse regenerated = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "regenerated-cv-document",
                cvOnlyRequest());

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                regenerated.state());
        assertEquals(true,
                regenerated.outputResults().get("CV").regeneration());
        verify(downstream).reserveSelected(
                eq(OWNER),
                eq(regenerated.operationId()),
                eq(DocumentPurpose.CV),
                eq(300L),
                eq(true));
        verify(downstream, times(2)).commit(
                OWNER, RESERVATION_ID, 300L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesNhsSourceMetadataWhenReusingAnExistingSavedApplication() {
        String externalJobId = "nhs-fixture-1";
        String listingUrl =
                "https://beta.jobs.nhs.uk/candidate/jobadvert/nhs-fixture-1";
        Map<String, Object> savedJob = new LinkedHashMap<>(
                savedJobResponse("SNAPSHOT"));
        Map<String, Object> job = new LinkedHashMap<>(
                (Map<String, Object>) savedJob.get("job"));
        job.put("provider", "NHS_JOBS");
        job.put("primarySource", "NHS_JOBS");
        job.put("externalJobId", externalJobId);
        job.put("sources", List.of(
                Map.of(
                        "provider", "NHS_JOBS",
                        "externalJobId", "different-vacancy",
                        "publisher", "Wrong source",
                        "listingUrl",
                        "https://wrong.example.test/listing",
                        "applyUrl",
                        "https://wrong.example.test/apply"),
                Map.of(
                        "integrationProvider", "NHS_JOBS",
                        "externalJobId", externalJobId,
                        "publisher", "NHS Jobs",
                        "listingUrl", listingUrl,
                        "applyUrl", listingUrl)));
        savedJob.put("job", job);
        when(downstream.savedJob(SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJob);

        doThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "Conflict",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .createApplication(
                        anyString(), anyString(), anyMap());
        when(downstream.applications(OWNER)).thenReturn(List.of(Map.of(
                "id", APPLICATION_ID.toString(),
                "canonicalJobId", "canonical-job-1",
                "status", "SAVED",
                "version", 1)));

        GenerationOperationResponse waiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "nhs-existing-application",
                cvOnlyRequest());

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                waiting.state());
        assertEquals(APPLICATION_ID, waiting.applicationId());
        ArgumentCaptor<Map> application =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).createApplication(
                eq(OWNER), anyString(), application.capture());
        assertEquals("NHS_JOBS", application.getValue().get("provider"));
        assertEquals(
                externalJobId,
                application.getValue().get("externalJobId"));
        assertEquals(listingUrl, application.getValue().get("listingUrl"));
        assertEquals(listingUrl, application.getValue().get("applyUrl"));
        assertEquals(
                "Vacancy source: NHS Jobs",
                application.getValue().get("attributionLabel"));
        assertEquals(
                "https://www.jobs.nhs.uk/",
                application.getValue().get("attributionSourceUrl"));
        assertEquals(
                "https://www.nationalarchives.gov.uk/doc/"
                        + "open-government-licence/version/3/",
                application.getValue().get("licenceUrl"));
        assertEquals(
                "NHS Jobs does not endorse Job Seeker Copilot.",
                application.getValue().get("disclaimer"));
        verify(downstream).applications(OWNER);
        verify(downstream).generateSelected(
                eq(OWNER), any(), eq(DocumentPurpose.CV), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicRecoverySummaryDropsRawGenerationAndUnknownCategories()
            throws Exception {
        stubSavedApplicationForSelectiveApproval();
        when(downstream.generateSelected(
                eq(OWNER), any(), eq(DocumentPurpose.CV), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> generated = new LinkedHashMap<>(
                            selectedGenerated(
                                    invocation.getArgument(1),
                                    DocumentPurpose.CV));
                    generated.put("content",
                            "private-prompt-evidence-sentinel");
                    Map<String, Object> recovery = new LinkedHashMap<>(
                            (Map<String, Object>) generated.get("recovery"));
                    recovery.put("retryReason",
                            "private-provider-error-sentinel");
                    recovery.put("fallbackReason",
                            "private-model-output-sentinel");
                    generated.put("recovery", recovery);
                    return generated;
                });

        GenerationOperationResponse waiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selected-public-recovery-allowlist",
                cvOnlyRequest());

        String publicJson = objectMapper.writeValueAsString(
                waiting.outputResults());
        assertFalse(publicJson.contains("private-prompt-evidence-sentinel"));
        assertFalse(publicJson.contains("private-provider-error-sentinel"));
        assertFalse(publicJson.contains("private-model-output-sentinel"));
        GenerationRecoverySummaryResponse summary = waiting
                .outputResults().get("CV").recoverySummary();
        assertNull(summary.retryReason());
        assertNull(summary.fallbackReason());
        verify(downstream, times(1)).commit(
                OWNER, RESERVATION_ID, 300L);
    }

    @Test
    void reconcilesAmbiguousSelectedCvFromRetainedResponseWithoutSecondProviderCall() {
        stubSavedApplicationForSelectiveApproval();
        AtomicReference<Runnable> reconciliation = new AtomicReference<>();
        when(workScheduler.submitAfter(
                any(), anyString(), any(Duration.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    reconciliation.set(invocation.getArgument(3));
                    return true;
                });
        doThrow(new ResourceAccessException("response lost"))
                .when(downstream)
                .generateSelected(
                        anyString(), any(), eq(DocumentPurpose.CV), anyMap());
        when(downstream.replayRejectedSelectedGeneration(
                eq(OWNER), any(), eq(DocumentPurpose.CV), anyMap()))
                .thenAnswer(invocation -> Map.of(
                        "outcome", "ACCEPTED",
                        "providerInvocationCount", 0,
                        "draft", selectedGenerated(
                                invocation.getArgument(1),
                                DocumentPurpose.CV)));
        DurableGenerationService recoveryEnabled =
                recoveryEnabledService(3);
        StartGenerationRequest request = cvOnlyRequest();

        GenerationOperationResponse accepted = recoveryEnabled.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selected-retained-reconciliation",
                request);
        GenerationOperationResponse pending = recoveryEnabled.get(
                OWNER, accepted.operationId());

        assertEquals(GenerationOperationState.APPLICATION_SAVED,
                pending.state());
        assertFalse(pending.manualActionRequired());
        assertNotNull(reconciliation.get());
        reconciliation.get().run();

        GenerationOperationResponse recovered = recoveryEnabled.get(
                OWNER, accepted.operationId());
        assertEquals(GenerationOperationState.AWAITING_APPROVAL,
                recovered.state());
        assertEquals(CV_DOCUMENT_ID, recovered.cvDocumentId());
        verify(downstream, times(1)).generateSelected(
                anyString(), any(), eq(DocumentPurpose.CV), anyMap());
        verify(downstream, times(1))
                .replayRejectedSelectedGeneration(
                        anyString(), any(), eq(DocumentPurpose.CV), anyMap());
        verify(downstream).commit(OWNER, RESERVATION_ID, 300L);
        GenerationRecoverySummaryResponse summary = recovered
                .outputResults().get("CV").recoverySummary();
        assertEquals("RECOVERED", summary.reconciliationStatus());
        assertEquals("RETAINED_RESPONSE",
                summary.reconciliationSource());
        assertTrue(summary.retainedResponseReplayed());
        assertEquals(1, summary.reconciliationAttempts());
        assertEquals("COMMITTED", summary.billingStatus());
        assertTrue(summary.charged());
    }

    @Test
    void releasesWalletReservationWhenReconciliationUsesFreeDeterministicCvFallback() {
        stubSavedApplicationForSelectiveApproval();
        AtomicReference<Runnable> reconciliation = new AtomicReference<>();
        when(workScheduler.submitAfter(
                any(), anyString(), any(Duration.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    reconciliation.set(invocation.getArgument(3));
                    return true;
                });
        doThrow(new ResourceAccessException("response lost"))
                .when(downstream)
                .generateSelected(
                        anyString(), any(), eq(DocumentPurpose.CV), anyMap());
        when(downstream.replayRejectedSelectedGeneration(
                eq(OWNER), any(), eq(DocumentPurpose.CV), anyMap()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND,
                        "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0],
                        java.nio.charset.StandardCharsets.UTF_8));
        when(downstream.deterministicSelectedFallback(
                eq(OWNER), any(), eq(DocumentPurpose.CV), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, Object> fallback = new LinkedHashMap<>(
                            selectedGenerated(
                                    invocation.getArgument(1),
                                    DocumentPurpose.CV));
                    fallback.put("billableTokens", 0L);
                    fallback.put("usage", Map.of(
                            "inputTokens", 0,
                            "outputTokens", 0,
                            "totalTokens", 0));
                    fallback.put("recovery", Map.of(
                            "finalSource", "DETERMINISTIC_FALLBACK",
                            "fallbackUsed", true));
                    return fallback;
                });
        DurableGenerationService recoveryEnabled =
                recoveryEnabledService(1);

        GenerationOperationResponse accepted = recoveryEnabled.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selected-fallback-reconciliation",
                cvOnlyRequest());
        assertNotNull(reconciliation.get());
        reconciliation.get().run();

        GenerationOperationResponse recovered = recoveryEnabled.get(
                OWNER, accepted.operationId());
        assertEquals(GenerationOperationState.AWAITING_APPROVAL,
                recovered.state());
        GenerationOutputResultResponse cv = recovered
                .outputResults().get("CV");
        assertEquals(0L, cv.actualTokens());
        assertEquals("RELEASED_NO_CHARGE", cv.billingOutcome());
        GenerationRecoverySummaryResponse summary = cv.recoverySummary();
        assertEquals("DETERMINISTIC_FALLBACK",
                summary.generationSource());
        assertTrue(summary.deterministicFallbackUsed());
        assertEquals("RECOVERED", summary.reconciliationStatus());
        assertEquals("DETERMINISTIC_FALLBACK",
                summary.reconciliationSource());
        assertEquals("RECONCILIATION_EXHAUSTED",
                summary.fallbackReason());
        assertEquals("RELEASED_NO_CHARGE",
                summary.billingStatus());
        assertFalse(summary.charged());
        assertTrue(summary.released());
        verify(downstream, never()).commit(anyString(), any(), anyLong());
        verify(downstream).release(
                OWNER,
                RESERVATION_ID,
                "DETERMINISTIC_FALLBACK_NO_CHARGE");
        verify(downstream, times(1)).generateSelected(
                anyString(), any(), eq(DocumentPurpose.CV), anyMap());
    }

    @Test
    void generatesOnlyAnExplicitlySelectedCoverLetter() {
        stubSavedApplicationForSelectiveApproval();
        StartGenerationRequest request = new StartGenerationRequest(
                Set.of(DocumentPurpose.COVER_LETTER),
                List.of(new DocumentEvidenceSelection(
                        DocumentPurpose.COVER_LETTER,
                        List.of(COVER_LETTER_EVIDENCE_ID),
                        List.of(EvidenceSection.VOLUNTEERING))));

        GenerationOperationResponse waiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "select-cover-letter-only",
                request);

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                waiting.state());
        assertNull(waiting.cvDocumentId());
        assertEquals(
                COVER_LETTER_DOCUMENT_ID,
                waiting.coverLetterDocumentId());
        verify(downstream, never()).generateSelected(
                anyString(), any(), eq(DocumentPurpose.CV), anyMap());
        verify(downstream).generateSelected(
                eq(OWNER),
                any(),
                eq(DocumentPurpose.COVER_LETTER),
                anyMap());
    }

    @Test
    void preservesSuccessfulCvWhenSelectedCoverLetterFails() {
        stubSavedApplicationForSelectiveApproval();
        when(downstream.generateSelected(
                anyString(),
                any(),
                eq(DocumentPurpose.COVER_LETTER),
                anyMap()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Unprocessable Entity",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0],
                        java.nio.charset.StandardCharsets.UTF_8));

        GenerationOperationResponse waiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "select-both-partial",
                new StartGenerationRequest(
                        Set.of(
                                DocumentPurpose.CV,
                                DocumentPurpose.COVER_LETTER),
                        selectionRequest().documents()));

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                waiting.state());
        assertEquals("PARTIAL_GENERATION", waiting.failureCode());
        assertEquals(CV_DOCUMENT_ID, waiting.cvDocumentId());
        assertNull(waiting.coverLetterDocumentId());
        assertEquals(
                "FAILED",
                waiting.outputResults().get("COVER_LETTER").status());
        GenerationRecoverySummaryResponse failedRecovery = waiting
                .outputResults().get("COVER_LETTER").recoverySummary();
        assertEquals("NOT_AVAILABLE",
                failedRecovery.generationSource());
        assertEquals("RELEASED_AFTER_FAILURE",
                failedRecovery.billingStatus());
        assertFalse(failedRecovery.charged());
        assertTrue(failedRecovery.released());
        verify(downstream).commit(OWNER, RESERVATION_ID, 300L);
        verify(downstream).release(
                eq(OWNER), any(), eq("GENERATION_REJECTED"));
        verify(downstream, times(2)).generateSelected(
                eq(OWNER), any(), any(DocumentPurpose.class), anyMap());
        verify(downstream, times(2)).reserveSelected(
                eq(OWNER), any(), any(DocumentPurpose.class), anyLong(),
                anyBoolean());

        GenerationOperationResponse completed = approveAndAwait(
                OWNER,
                waiting.operationId(),
                new ApproveGenerationRequest(CV_DOCUMENT_ID, null));
        assertEquals(GenerationOperationState.COMPLETED, completed.state());
        assertEquals("PARTIAL_GENERATION", completed.failureCode());
    }

    @Test
    void preservesSuccessfulCvWhenSiblingEvidenceIsInvalid() {
        stubSavedApplicationForSelectiveApproval();
        when(downstream.evidenceSnapshot(
                any(DocumentEvidenceSelection.class)))
                .thenAnswer(invocation -> {
                    DocumentEvidenceSelection requested =
                            invocation.getArgument(0);
                    if (requested.purpose()
                            == DocumentPurpose.COVER_LETTER) {
                        return Map.of("purpose", "COVER_LETTER");
                    }
                    return evidenceSnapshot(requested);
                });

        GenerationOperationResponse waiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "select-both-invalid-sibling-evidence",
                new StartGenerationRequest(
                        Set.of(
                                DocumentPurpose.CV,
                                DocumentPurpose.COVER_LETTER),
                        selectionRequest().documents()));

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                waiting.state());
        assertEquals(CV_DOCUMENT_ID, waiting.cvDocumentId());
        assertNull(waiting.coverLetterDocumentId());
        assertEquals("PARTIAL_GENERATION", waiting.failureCode());
        assertEquals(
                "FAILED",
                waiting.outputResults().get("COVER_LETTER").status());
        verify(downstream, never()).estimateSelected(
                anyString(), eq(DocumentPurpose.COVER_LETTER), anyMap());
        verify(downstream, never()).reserveSelected(
                anyString(), any(), eq(DocumentPurpose.COVER_LETTER),
                anyLong(), anyBoolean());
        verify(downstream, never()).generateSelected(
                anyString(), any(), eq(DocumentPurpose.COVER_LETTER), anyMap());
    }

    @Test
    void rejectsInvalidExplicitSelectionsBeforeAnyDownstreamCall() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.start(
                        OWNER,
                        AUTHORIZATION,
                        SAVED_JOB_ID,
                        "select-empty",
                        new StartGenerationRequest(
                                Set.of(), List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.start(
                        OWNER,
                        AUTHORIZATION,
                        SAVED_JOB_ID,
                        "select-mismatch",
                        new StartGenerationRequest(
                                Set.of(DocumentPurpose.CV),
                                List.of(new DocumentEvidenceSelection(
                                        DocumentPurpose.COVER_LETTER,
                                        List.of(COVER_LETTER_EVIDENCE_ID),
                                        List.of(EvidenceSection.VOLUNTEERING))))));

        verify(downstream, never()).savedJob(any(), anyString());
        verify(downstream, never()).estimateSelected(
                anyString(), any(), anyMap());
        verify(downstream, never()).reserveSelected(
                anyString(), any(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generateSelected(
                anyString(), any(), any(), anyMap());
    }

    @Test
    void replayRecoversDuplicateCanonicalApplicationWithoutRegeneration() {
        var generated = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "duplicate-application-1",
                selectionRequest());
        doThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "Conflict",
                org.springframework.http.HttpHeaders.EMPTY,
                """
                {"status":409,"message":"An application for this canonical job is already tracked for the owner."}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .createApplication(
                        anyString(), anyString(), anyMap());

        var recoveryRequired = approveAndAwait(
                OWNER,
                generated.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                recoveryRequired.state());
        assertEquals(
                "APPLICATION_LINK_RECOVERY_REQUIRED",
                recoveryRequired.failureCode());
        assertEquals(
                "The generated documents could not be linked to the "
                        + "existing saved application automatically.",
                recoveryRequired.failureMessage());

        when(downstream.applications(OWNER)).thenReturn(List.of(
                Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 3)));
        when(downstream.updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                generated.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 4));
        when(downstream.updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                4))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "DOCUMENTS_GENERATED",
                        "version", 5));

        var recovered = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "duplicate-application-retry",
                selectionRequest());

        assertEquals(
                generated.operationId(),
                recovered.operationId());
        assertEquals(
                GenerationOperationState.COMPLETED,
                recovered.state());
        assertEquals(APPLICATION_ID, recovered.applicationId());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, times(1))
                .commit(OWNER, RESERVATION_ID, 600);
        verify(downstream).updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                generated.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID);
        verify(downstream).updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                4);
    }

    @Test
    void relinksNewDocumentsToAnExistingUnappliedGeneratedApplication() {
        var generated = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "existing-generated-application-1",
                selectionRequest());
        doThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "Conflict",
                org.springframework.http.HttpHeaders.EMPTY,
                """
                {"status":409,"message":"An application for this canonical job is already tracked for the owner."}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .createApplication(
                        anyString(), anyString(), anyMap());
        when(downstream.applications(OWNER)).thenReturn(List.of(
                Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "DOCUMENTS_GENERATED",
                        "cvDocumentId", UUID.randomUUID().toString(),
                        "coverLetterDocumentId",
                                UUID.randomUUID().toString(),
                        "version", 3)));
        when(downstream.updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                generated.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "DOCUMENTS_GENERATED",
                        "cvDocumentId", CV_DOCUMENT_ID.toString(),
                        "coverLetterDocumentId",
                                COVER_LETTER_DOCUMENT_ID.toString(),
                        "version", 4));

        var completed = approveAndAwait(
                OWNER,
                generated.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        assertEquals(APPLICATION_ID, completed.applicationId());
        verify(downstream).updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                generated.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID);
        verify(downstream, never()).updateApplicationStatus(
                anyString(), any(), anyString(), anyLong());
        verify(downstream, times(1)).generate(
                anyString(), any(), anyMap());
    }

    @Test
    void startReturnsPersistedOperationBeforeQueuedWorkRuns() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        reset(workScheduler);
        when(workScheduler.submit(any(), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(2));
                    return true;
                });

        var accepted = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "asynchronous-start-1",
                selectionRequest());

        assertEquals(GenerationOperationState.CREATED, accepted.state());
        assertEquals(
                GenerationOperationState.CREATED,
                service.get(OWNER, accepted.operationId()).state());
        verify(downstream, never()).savedJob(any(), anyString());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        queued.get().run();

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                service.get(OWNER, accepted.operationId()).state());
    }

    @Test
    void approvalIntentIsPersistedBeforeQueuedWorkRuns() {
        var awaiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "asynchronous-approval-1",
                selectionRequest());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        reset(workScheduler);
        when(workScheduler.submit(any(), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(2));
                    return true;
                });

        var accepted = service.approve(
                OWNER,
                awaiting.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                accepted.state());
        GenerationOperation persisted = repository
                .findByOwnerAndId(awaiting.operationId(), OWNER)
                .orElseThrow();
        assertTrue(persisted.data().containsKey("approvalRequest"));
        verify(downstream, never()).approveDocument(
                anyString(), any());

        queued.get().run();

        assertEquals(
                GenerationOperationState.COMPLETED,
                service.get(OWNER, awaiting.operationId()).state());
    }

    @Test
    void queuedStartReplayCanFinishANewlyPersistedApprovalIntent() {
        AtomicReference<Runnable> queuedStart = new AtomicReference<>();
        reset(workScheduler);
        when(workScheduler.submit(any(), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    queuedStart.set(invocation.getArgument(2));
                    return true;
                });
        var accepted = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "start-approval-handoff-1",
                selectionRequest());
        var synchronousService = new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                deadlineGuard,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        var awaiting = startAndAwait(
                synchronousService,
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "start-approval-handoff-1",
                selectionRequest());
        GenerationOperation operation = repository
                .findByOwnerAndId(accepted.operationId(), OWNER)
                .orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>(operation.data());
        data.put("approvalRequest", Map.of(
                "cvDocumentId", CV_DOCUMENT_ID.toString(),
                "coverLetterDocumentId",
                COVER_LETTER_DOCUMENT_ID.toString()));
        repository.acceptApproval(operation, data);

        queuedStart.get().run();

        assertEquals(
                awaiting.operationId(), accepted.operationId());
        assertEquals(
                GenerationOperationState.COMPLETED,
                service.get(OWNER, accepted.operationId()).state());
        verify(downstream, times(1)).createApplication(
                anyString(), anyString(), anyMap());
    }

    @Test
    void approvalAcceptanceRecoversFromAConcurrentVersionChange()
            throws Exception {
        var awaitingResponse = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "approval-version-race-1",
                selectionRequest());
        GenerationOperation stale = repository
                .findByOwnerAndId(
                        awaitingResponse.operationId(), OWNER)
                .orElseThrow();
        Map<String, Object> concurrentData =
                new LinkedHashMap<>(stale.data());
        concurrentData.put("concurrentMarker", "retained");
        assertEquals(
                1,
                jdbc.update("""
                        UPDATE generation_operations
                           SET data_json = ?, version = version + 1
                         WHERE id = ? AND owner_id = ?
                        """,
                        objectMapper.writeValueAsString(concurrentData),
                        stale.id(),
                        stale.ownerId()));
        Map<String, Object> approvalRequest = Map.of(
                "cvDocumentId", CV_DOCUMENT_ID.toString(),
                "coverLetterDocumentId",
                COVER_LETTER_DOCUMENT_ID.toString());
        Map<String, Object> requestedData =
                new LinkedHashMap<>(stale.data());
        requestedData.put("approvalRequest", approvalRequest);

        GenerationOperation accepted =
                repository.acceptApproval(stale, requestedData);

        assertEquals("retained", accepted.data().get("concurrentMarker"));
        assertEquals(
                approvalRequest, accepted.data().get("approvalRequest"));
        GenerationOperation replay =
                repository.acceptApproval(stale, requestedData);
        assertEquals(accepted.version(), replay.version());

        Map<String, Object> differentData =
                new LinkedHashMap<>(stale.data());
        differentData.put("approvalRequest", Map.of(
                "cvDocumentId", UUID.randomUUID().toString(),
                "coverLetterDocumentId",
                COVER_LETTER_DOCUMENT_ID.toString()));
        assertThrows(
                GenerationConflictException.class,
                () -> repository.acceptApproval(
                        stale, differentData));
    }

    @Test
    void authoritativeGetResubmitsPersistedApprovalAfterAStaleLease() {
        var awaiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "approval-restart-1",
                selectionRequest());
        UUID staleLease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                awaiting.operationId(),
                OWNER,
                staleLease,
                Duration.ofSeconds(30)));

        var accepted = service.approve(
                OWNER,
                awaiting.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                accepted.state());
        assertTrue(repository.findByOwnerAndId(
                        awaiting.operationId(), OWNER)
                .orElseThrow()
                .data()
                .containsKey("approvalRequest"));
        repository.release(
                awaiting.operationId(), OWNER, staleLease);

        var firstPoll = service.get(
                OWNER, awaiting.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                firstPoll.state());
        assertEquals(
                GenerationOperationState.COMPLETED,
                service.get(OWNER, awaiting.operationId()).state());
        verify(downstream, times(1)).createApplication(
                anyString(), anyString(), anyMap());
    }

    @Test
    void replayOnANewServiceInstanceResumesTheSamePreProviderOperation() {
        reset(workScheduler);
        when(workScheduler.submit(any(), anyString(), any(Runnable.class)))
                .thenReturn(false);
        var stalled = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "restart-replay-1",
                selectionRequest());
        assertEquals(GenerationOperationState.CREATED, stalled.state());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        var restartedService = new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                deadlineGuard,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        var replay = restartedService.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "restart-replay-1",
                selectionRequest());

        assertEquals(stalled.operationId(), replay.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                restartedService.get(
                        OWNER, replay.operationId()).state());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
    }

    @Test
    void replayDefersPreProviderResumeUntilAStaleLeaseExpires() {
        reset(workScheduler);
        when(workScheduler.submit(any(), anyString(), any(Runnable.class)))
                .thenReturn(false);
        var stalled = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "stale-lease-replay-1",
                selectionRequest());
        UUID staleLease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                stalled.operationId(),
                OWNER,
                staleLease,
                Duration.ofSeconds(30)));

        AtomicReference<Runnable> deferred = new AtomicReference<>();
        reset(workScheduler);
        when(workScheduler.submit(any(), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(2, Runnable.class).run();
                    return true;
                });
        when(workScheduler.submitAfter(
                any(),
                anyString(),
                any(Duration.class),
                any(Runnable.class)))
                .thenAnswer(invocation -> {
                    deferred.set(invocation.getArgument(3));
                    return true;
                });

        var replay = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "stale-lease-replay-1",
                selectionRequest());
        assertEquals(stalled.operationId(), replay.operationId());
        assertTrue(deferred.get() != null);
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        repository.release(
                stalled.operationId(), OWNER, staleLease);
        deferred.get().run();

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                service.get(OWNER, stalled.operationId()).state());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
    }

    @Test
    void cancellationWinsBeforeQueuedWorkAndRemainsAuthoritative() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        reset(workScheduler);
        when(workScheduler.submit(any(), anyString(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(2));
                    return true;
                });
        var accepted = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "queued-cancellation-1",
                selectionRequest());

        var cancelled = service.cancel(
                OWNER, accepted.operationId());
        queued.get().run();

        assertEquals(
                GenerationOperationState.CANCELLED,
                cancelled.state());
        assertEquals(
                GenerationOperationState.CANCELLED,
                service.get(OWNER, accepted.operationId()).state());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());
    }

    @Test
    void retryAcceptsMatchingDocumentsWhenStatusUpdateOutcomeWasUnknown() {
        String idempotencyKey = "ambiguous-status-update-1";
        var generated = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                idempotencyKey,
                selectionRequest());
        doThrow(HttpClientErrorException.create(
                HttpStatus.CONFLICT,
                "Conflict",
                org.springframework.http.HttpHeaders.EMPTY,
                """
                {"status":409,"message":"An application for this canonical job is already tracked for the owner."}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .createApplication(
                        anyString(), anyString(), anyMap());
        when(downstream.applications(OWNER)).thenReturn(
                List.of(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 3)),
                List.of(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "DOCUMENTS_GENERATED",
                        "cvDocumentId", CV_DOCUMENT_ID.toString(),
                        "coverLetterDocumentId",
                                COVER_LETTER_DOCUMENT_ID.toString(),
                        "version", 6)));
        when(downstream.updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                generated.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "status", "SAVED",
                        "version", 4));
        when(downstream.updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                4))
                .thenThrow(new ResourceAccessException(
                        "status response was lost"));

        var ambiguous = approveAndAwait(
                OWNER,
                generated.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.EXPORTED,
                ambiguous.state());
        assertEquals("DOWNSTREAM_RETRYABLE", ambiguous.failureCode());

        var recovered = approveAndAwait(
                OWNER,
                generated.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.COMPLETED,
                recovered.state());
        assertEquals(APPLICATION_ID, recovered.applicationId());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, times(1)).updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                generated.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID);
        verify(downstream, times(1)).updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                4);
    }

    @Test
    void sendsRevisionBoundSkillsAndPurposeSpecificEvidenceSnapshotsToGeneration() {
        startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "purpose-specific-evidence-1",
                selectionRequest());

        ArgumentCaptor<Map> generationRequest =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).generate(
                org.mockito.ArgumentMatchers.eq(OWNER),
                any(),
                generationRequest.capture());
        Map<?, ?> request = generationRequest.getValue();
        assertEquals("2.0", request.get("inputSchemaVersion"));
        Map<?, ?> profile = (Map<?, ?>) request.get("profile");
        assertEquals(
                List.of("Java", "PostgreSQL"),
                profile.get("skills"));
        assertEquals(List.of(), profile.get("qualifications"));
        assertEquals(List.of(), profile.get("employmentHistory"));

        Map<?, ?> snapshots =
                (Map<?, ?>) request.get("evidenceSnapshots");
        Map<?, ?> cv = (Map<?, ?>) snapshots.get("cv");
        Map<?, ?> coverLetter =
                (Map<?, ?>) snapshots.get("coverLetter");
        assertEquals("CV", cv.get("purpose"));
        assertEquals("COVER_LETTER", coverLetter.get("purpose"));
        assertEquals(
                CV_EVIDENCE_ID.toString(),
                ((Map<?, ?>) ((List<?>) cv.get("selections")).get(0))
                        .get("entryId"));
        assertEquals(
                COVER_LETTER_EVIDENCE_ID.toString(),
                ((Map<?, ?>) ((List<?>) coverLetter.get("selections")).get(0))
                        .get("entryId"));

        ArgumentCaptor<Map> documents =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream, times(2)).createDocument(
                org.mockito.ArgumentMatchers.eq(OWNER),
                anyString(),
                documents.capture());
        Map<?, ?> cvDocument = documents.getAllValues().stream()
                .filter(value -> "CV".equals(value.get("documentType")))
                .findFirst()
                .orElseThrow();
        Map<?, ?> coverDocument = documents.getAllValues().stream()
                .filter(value -> "COVER_LETTER".equals(
                        value.get("documentType")))
                .findFirst()
                .orElseThrow();
        Map<?, ?> cvProvenance =
                (Map<?, ?>) cvDocument.get("evidenceProvenance");
        Map<?, ?> coverProvenance =
                (Map<?, ?>) coverDocument.get("evidenceProvenance");
        assertEquals(
                "90000000-0000-4000-8000-000000000001",
                cvProvenance.get("evidenceSnapshotId").toString());
        assertEquals(
                "90000000-0000-4000-8000-000000000002",
                coverProvenance.get("evidenceSnapshotId").toString());
        assertEquals(
                List.of("PROJECT"),
                cvProvenance.get("sectionOrder"));
        assertEquals(
                List.of("VOLUNTEERING"),
                coverProvenance.get("sectionOrder"));
        assertEquals(
                "70000000-0000-4000-8000-000000000001",
                ((Map<?, ?>) ((List<?>) cvProvenance.get(
                        "evidenceRevisions")).get(0))
                        .get("revisionId")
                        .toString());
        assertEquals(
                "c".repeat(64),
                ((Map<?, ?>) cvProvenance.get("claimLedger"))
                        .get("ledgerSha256"));
        assertEquals(
                cvProvenance.get("generatedAt"),
                coverProvenance.get("generatedAt"));
    }

    @Test
    void keepsRevisionedProfessionalContactOutOfGenerationAndCarriesItToExport() {
        var waiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "professional-contact-pipeline-1",
                selectionRequest());

        ArgumentCaptor<Map> generationRequest =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).generate(
                eq(OWNER), any(), generationRequest.capture());
        Map<?, ?> profile = (Map<?, ?>) generationRequest
                .getValue().get("profile");
        assertFalse(profile.containsKey("professionalContact"));
        assertFalse(generationRequest.getValue().toString()
                .contains("7946"));
        assertFalse(generationRequest.getValue().toString()
                .contains("github.com"));
        ArgumentCaptor<Map> estimateRequest =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).estimate(eq(OWNER), estimateRequest.capture());
        assertFalse(estimateRequest.getValue().toString().contains("7946"));
        assertFalse(estimateRequest.getValue().toString()
                .contains("github.com"));

        approveAndAwait(
                OWNER,
                waiting.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));
        ArgumentCaptor<Map> exportedContact =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream, times(2)).exportDocument(
                eq(OWNER), any(), anyString(), exportedContact.capture());
        for (Map<?, ?> professionalContact : exportedContact.getAllValues()) {
            assertEquals(
                    "+44 20 7946 0958",
                    professionalContact.get("phone"));
            assertEquals(
                    "https://github.com/example",
                    ((Map<?, ?>) ((List<?>) professionalContact
                            .get("links")).get(0)).get("url"));
        }
    }

    @Test
    void prioritizesPunctuatedJobRelevantSkillsBeforeTheBoundedGenerationLimit() {
        List<String> skills = new ArrayList<>();
        for (int index = 1; index <= 40; index++) {
            skills.add("Unrelated skill " + index);
        }
        skills.add("Java");
        skills.add(".NET");
        skills.add("Node.js");
        when(downstream.profile()).thenReturn(Map.of(
                "userId", OWNER,
                "revisionId",
                "60000000-0000-4000-8000-000000000001",
                "contentDigest", "f".repeat(64),
                "skills", skills,
                "aspirations", Map.of(
                        "targetRoles", List.of("Backend Developer")),
                "workPreferences", Map.of()));
        Map<String, Object> savedJob = new LinkedHashMap<>(
                savedJobResponse("SNAPSHOT"));
        Map<String, Object> job = new LinkedHashMap<>(
                (Map<String, Object>) savedJob.get("job"));
        job.put("title", "Backend Developer");
        job.put(
                "description",
                "Experience with .NET, Node.js and Java.");
        savedJob.put("job", job);
        when(downstream.savedJob(SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJob);

        startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "relevant-skill-selection-1",
                selectionRequest());

        ArgumentCaptor<Map> generationRequest =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).generate(
                org.mockito.ArgumentMatchers.eq(OWNER),
                any(),
                generationRequest.capture());
        Map<?, ?> profile = (Map<?, ?>) generationRequest
                .getValue()
                .get("profile");
        List<?> selected = (List<?>) profile.get("skills");
        assertEquals(40, selected.size());
        assertEquals(List.of("Java", ".NET", "Node.js"),
                selected.subList(0, 3));
        assertTrue(selected.contains("Unrelated skill 1"));
        assertFalse(selected.contains("Unrelated skill 40"));
    }

    @Test
    void idempotencyKeyCannotBeReusedForDifferentEvidenceSelection() {
        startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selection-conflict-1",
                selectionRequest());

        assertThrows(
                GenerationConflictException.class,
                () -> startAndAwait(
                        OWNER,
                        AUTHORIZATION,
                        SAVED_JOB_ID,
                        "selection-conflict-1",
                        selectionRequest(
                                UUID.randomUUID(),
                                COVER_LETTER_EVIDENCE_ID)));
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
    }

    @Test
    void claimantCanGenerateAgainForTheSameJobWithANewSelection() {
        var first = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selection-a",
                selectionRequest());
        var second = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selection-b",
                selectionRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID()));

        assertFalse(first.operationId().equals(second.operationId()));
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                second.state());
        verify(downstream, times(2))
                .generate(anyString(), any(), anyMap());
    }

    @Test
    void normalizesARealProviderUkPostedDateForTheCvContract() {
        when(downstream.savedJob(
                SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJobResponse(
                        "SNAPSHOT", "30/06/2026"));

        var operation = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "provider-date-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                operation.state());
        ArgumentCaptor<Map> request =
                ArgumentCaptor.forClass(Map.class);
        verify(downstream).estimate(
                org.mockito.ArgumentMatchers.eq(OWNER),
                request.capture());
        Map<?, ?> job = (Map<?, ?>) request.getValue().get("job");
        assertEquals("2026-06-30", job.get("postedDate"));
    }

    @Test
    void concurrentSameJobRequestsPerformOnlyOneModelInvocation() throws Exception {
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        doAnswer(invocation -> {
                    generationEntered.countDown();
                    assertTrue(releaseGeneration.await(
                            5, TimeUnit.SECONDS));
                    return generated(invocation.getArgument(1));
                })
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> startAndAwait(
                    OWNER,
                    AUTHORIZATION,
                    SAVED_JOB_ID,
                    "concurrent-key-a",
                    selectionRequest()));
            assertTrue(generationEntered.await(
                    5, TimeUnit.SECONDS));
            var duplicate = executor.submit(() -> startAndAwait(
                    OWNER,
                    AUTHORIZATION,
                    SAVED_JOB_ID,
                    "concurrent-key-a",
                    selectionRequest()));
            var duplicateResponse = duplicate.get(
                    5, TimeUnit.SECONDS);
            assertEquals(
                    GenerationOperationState.GENERATION_IN_PROGRESS,
                    duplicateResponse.state());
            releaseGeneration.countDown();
            assertEquals(
                    GenerationOperationState.AWAITING_APPROVAL,
                    first.get(5, TimeUnit.SECONDS).state());
        } finally {
            executor.shutdownNow();
        }

        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                startAndAwait(
                        OWNER,
                        AUTHORIZATION,
                        SAVED_JOB_ID,
                        "concurrent-key-a",
                        selectionRequest()).state());
    }

    @Test
    void ambiguousGenerationIsNeverRetriedOrReleasedAutomatically() {
        doThrow(new ResourceAccessException(
                        "connection closed after request"))
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var unknown = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "ambiguous-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.GENERATION_OUTCOME_UNKNOWN,
                unknown.state());
        assertTrue(unknown.manualActionRequired());
        assertEquals(
                "GENERATION_OUTCOME_UNKNOWN",
                unknown.failureCode());

        var replay = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "ambiguous-1",
                selectionRequest());
        assertEquals(unknown.operationId(), replay.operationId());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, never()).commit(
                anyString(), any(), anyLong());
        verify(downstream, never()).release(
                anyString(), any(), anyString());
    }

    @Test
    void expiredReplaySafeStoreFailureResumesWithTheSameKeys() {
        AtomicInteger coverLetterAttempts = new AtomicInteger();
        List<String> documentKeys = new ArrayList<>();
        List<String> generatedAtValues = new ArrayList<>();
        doAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    Map<String, Object> request =
                            invocation.getArgument(2);
                    documentKeys.add(key);
                    generatedAtValues.add(((Map<?, ?>) request.get(
                            "evidenceProvenance")).get(
                                    "generatedAt").toString());
                    if ("COVER_LETTER".equals(
                            request.get("documentType"))
                            && coverLetterAttempts
                            .getAndIncrement() == 0) {
                        throw new ResourceAccessException(
                                "store unavailable");
                    }
                    return documentResponse(request);
                })
                .when(downstream)
                .createDocument(anyString(), anyString(), anyMap());

        var interrupted = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "store-retry-1",
                selectionRequest());
        assertEquals(
                GenerationOperationState.DRAFT_GENERATED,
                interrupted.state());
        assertEquals(
                "DOWNSTREAM_RETRYABLE",
                interrupted.failureCode());

        Instant expiredDeadline = Instant.now().minusSeconds(1);
        assertEquals(
                1,
                jdbc.update("""
                        UPDATE generation_operations
                           SET deadline_at = ?
                         WHERE id = ? AND owner_id = ?
                        """,
                        OffsetDateTime.ofInstant(
                                expiredDeadline, ZoneOffset.UTC),
                        interrupted.operationId(),
                        OWNER));

        AtomicReference<Runnable> resumedWork = new AtomicReference<>();
        reset(workScheduler);
        doAnswer(invocation -> {
                    resumedWork.set(
                            invocation.getArgument(2, Runnable.class));
                    return true;
                })
                .when(workScheduler)
                .submit(any(), anyString(), any(Runnable.class));

        var prepared = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "store-retry-2",
                selectionRequest());
        assertEquals(interrupted.operationId(), prepared.operationId());
        assertEquals(
                GenerationOperationState.DRAFT_GENERATED,
                prepared.state());
        assertNull(prepared.failureCode());
        assertTrue(prepared.deadlineAt().isAfter(Instant.now()));
        assertNotNull(resumedWork.get());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, never())
                .commit(OWNER, RESERVATION_ID, 600);

        resumedWork.get().run();
        var resumed = service.get(OWNER, prepared.operationId());
        assertEquals(interrupted.operationId(), resumed.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                resumed.state());
        assertEquals(
                List.of(
                        resumed.operationId() + ":cv-document",
                        resumed.operationId()
                                + ":cover-letter-document",
                        resumed.operationId() + ":cv-document",
                        resumed.operationId()
                                + ":cover-letter-document"),
                documentKeys);
        assertEquals(1, new HashSet<>(generatedAtValues).size());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, times(1))
                .commit(OWNER, RESERVATION_ID, 600);
    }

    @Test
    void legacyDeadlineRecoveryResumesAfterGenerationWithoutAnotherModelCall() {
        AtomicInteger coverLetterAttempts = new AtomicInteger();
        List<String> documentKeys = new ArrayList<>();
        doAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    Map<String, Object> request =
                            invocation.getArgument(2);
                    documentKeys.add(key);
                    if ("COVER_LETTER".equals(
                            request.get("documentType"))
                            && coverLetterAttempts
                            .getAndIncrement() == 0) {
                        throw new ResourceAccessException(
                                "store unavailable");
                    }
                    return documentResponse(request);
                })
                .when(downstream)
                .createDocument(anyString(), anyString(), anyMap());

        var interrupted = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID,
                "legacy-deadline-recovery",
                selectionRequest());
        assertEquals(
                GenerationOperationState.DRAFT_GENERATED,
                interrupted.state());
        assertEquals(
                "DOWNSTREAM_RETRYABLE",
                interrupted.failureCode());
        assertEquals(
                1,
                jdbc.update("""
                        UPDATE generation_operations
                           SET state = 'RECOVERY_REQUIRED',
                               failure_code =
                                   'OPERATION_DEADLINE_RECOVERY_REQUIRED',
                               failure_message = 'Retained legacy recovery',
                               deadline_at = ?, version = version + 1
                         WHERE id = ? AND owner_id = ?
                        """,
                        OffsetDateTime.ofInstant(
                                Instant.now().minusSeconds(1),
                                ZoneOffset.UTC),
                        interrupted.operationId(),
                        OWNER));

        AtomicReference<Runnable> resumedWork = new AtomicReference<>();
        reset(workScheduler);
        doAnswer(invocation -> {
                    resumedWork.set(
                            invocation.getArgument(2, Runnable.class));
                    return true;
                })
                .when(workScheduler)
                .submit(any(), anyString(), any(Runnable.class));

        var prepared = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID,
                "fresh-browser-key-after-terminal-state",
                selectionRequest());
        assertEquals(interrupted.operationId(), prepared.operationId());
        assertEquals(
                GenerationOperationState.DRAFT_GENERATED,
                prepared.state());
        assertNull(prepared.failureCode());
        assertTrue(prepared.deadlineAt().isAfter(Instant.now()));
        assertNotNull(resumedWork.get());

        resumedWork.get().run();
        var resumed = service.get(OWNER, prepared.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                resumed.state());
        assertEquals(
                List.of(
                        resumed.operationId() + ":cv-document",
                        resumed.operationId()
                                + ":cover-letter-document",
                        resumed.operationId() + ":cv-document",
                        resumed.operationId()
                                + ":cover-letter-document"),
                documentKeys);
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, times(1))
                .commit(OWNER, RESERVATION_ID, 600);
    }

    @Test
    void restartResumesDraftGeneratedWithoutAnotherModelInvocation() {
        AtomicInteger commitAttempts = new AtomicInteger();
        doAnswer(invocation -> {
                    if (commitAttempts.getAndIncrement() == 0) {
                        throw new ResourceAccessException(
                                "commit unavailable");
                    }
                    return null;
                })
                .when(downstream)
                .commit(OWNER, RESERVATION_ID, 600);

        var interrupted = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "draft-generated-restart-a",
                selectionRequest());
        assertEquals(
                GenerationOperationState.DRAFTS_STORED_PENDING_CREDIT,
                interrupted.state());
        assertEquals(
                "DOWNSTREAM_RETRYABLE",
                interrupted.failureCode());

        var restartedService = new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                deadlineGuard,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        var resumed = startAndAwait(
                restartedService,
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "draft-generated-restart-b",
                selectionRequest());

        assertEquals(interrupted.operationId(), resumed.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                resumed.state());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, times(1)).reserve(
                OWNER, resumed.operationId(), 1000, false);
        verify(downstream, times(2))
                .commit(OWNER, RESERVATION_ID, 600);
    }

    @Test
    void restartPromotesPersistedDraftsStoredWithoutRepeatingSideEffects() {
        var awaiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "drafts-stored-restart-a",
                selectionRequest());
        jdbc.update("""
                UPDATE generation_operations
                   SET state = 'DRAFTS_STORED',
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND owner_id = ?
                """,
                awaiting.operationId(),
                OWNER);

        var restartedService = new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                deadlineGuard,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1));
        var resumed = startAndAwait(
                restartedService,
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "drafts-stored-restart-b",
                selectionRequest());

        assertEquals(awaiting.operationId(), resumed.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                resumed.state());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, times(1))
                .commit(OWNER, RESERVATION_ID, 600);
        verify(downstream, times(2)).createDocument(
                anyString(), anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAnOversizedClaimBeforeCallingTheDocumentStore() {
        doAnswer(invocation -> {
                    Map<String, Object> response = new LinkedHashMap<>(
                            generated(invocation.getArgument(1)));
                    Map<String, Object> ledger = new LinkedHashMap<>(
                            (Map<String, Object>) response.get(
                                    "claimLedger"));
                    List<Map<String, Object>> claims = new ArrayList<>(
                            (List<Map<String, Object>>) ledger.get(
                                    "claims"));
                    Map<String, Object> oversized = new LinkedHashMap<>(
                            claims.get(0));
                    oversized.put(
                            "contentPaths",
                            java.util.stream.IntStream.range(0, 31)
                                    .mapToObj(index ->
                                            "/cv/coreSkills/"
                                                    + index
                                                    + "/name")
                                    .toList());
                    claims.set(0, oversized);
                    ledger.put("claims", claims);
                    response.put("claimLedger", ledger);
                    return response;
                })
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var rejected = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "oversized-claim-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.FAILED,
                rejected.state());
        assertEquals(
                "INVALID_DOWNSTREAM_RESPONSE",
                rejected.failureCode());
        verify(downstream, never()).createDocument(
                anyString(), anyString(), anyMap());
    }

    @Test
    void releasesCreditWhenTheModelOutputCannotBeGrounded() {
        doThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unprocessable Entity",
                org.springframework.http.HttpHeaders.EMPTY,
                "{\"message\":\"alex@example.com unsupported claim\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        org.slf4j.MDC.put(
                "correlationId", "failure-correlation-1");
        GenerationOperationResponse rejected;
        try {
            rejected = startAndAwait(
                    OWNER,
                    AUTHORIZATION,
                    SAVED_JOB_ID,
                    "ungrounded-output-1",
                    selectionRequest());
        } finally {
            org.slf4j.MDC.clear();
        }

        assertEquals(
                GenerationOperationState.FAILED,
                rejected.state());
        assertEquals(
                "GENERATION_REJECTED",
                rejected.failureCode());
        assertEquals(
                "Document generation was rejected before a usable "
                        + "draft was returned.",
                rejected.failureMessage());
        assertFalse(rejected.failureMessage().contains(
                "alex@example.com"));
        assertEquals(
                "failure-correlation-1",
                repository.findByOwnerAndId(
                                rejected.operationId(), OWNER)
                        .orElseThrow()
                        .data()
                        .get("correlationId"));
        verify(downstream).release(
                OWNER,
                RESERVATION_ID,
                "GENERATION_REJECTED");
        verify(downstream, never()).commit(
                anyString(), any(), anyLong());
    }

    @Test
    void publishesAnAcceptedRetainedResponseWithoutAnotherProviderCall() {
        doThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unprocessable Entity",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .generate(anyString(), any(), anyMap());
        var rejected = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "retained-response-recovery-1",
                selectionRequest());
        assertEquals(
                GenerationOperationState.FAILED,
                rejected.state());

        UUID recoveryReservation = UUID.fromString(
                "20000000-0000-0000-0000-000000000099");
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("outcome", "ACCEPTED");
        replay.put("providerInvocationCount", 0);
        replay.put("diagnostic", null);
        Map<String, Object> draft = new LinkedHashMap<>(
                generated(rejected.operationId()));
        draft.put("usage", Map.of(
                "inputTokens", 30_945,
                "outputTokens", 6_853,
                "totalTokens", 37_798));
        replay.put("draft", draft);
        when(downstream.replayRejectedGeneration(
                eq(OWNER), eq(rejected.operationId()), anyMap()))
                .thenReturn(replay);
        when(downstream.reserveRetainedResponseRecovery(
                OWNER, rejected.operationId(), 37_798))
                .thenReturn(Map.of(
                        "reservationId",
                        recoveryReservation.toString(),
                        "status", "RESERVED"));

        service.recoverRejectedGeneration(
                OWNER, rejected.operationId());
        var completed = service.get(
                OWNER, rejected.operationId());

        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        assertEquals(CV_DOCUMENT_ID, completed.cvDocumentId());
        assertEquals(
                COVER_LETTER_DOCUMENT_ID,
                completed.coverLetterDocumentId());
        assertEquals(APPLICATION_ID, completed.applicationId());
        verify(downstream, times(1)).generate(
                anyString(), any(), anyMap());
        verify(downstream, times(1)).replayRejectedGeneration(
                eq(OWNER), eq(rejected.operationId()), anyMap());
        verify(downstream).commit(
                OWNER, recoveryReservation, 37_798);
        verify(downstream).approveDocument(
                OWNER, CV_DOCUMENT_ID);
        verify(downstream).approveDocument(
                OWNER, COVER_LETTER_DOCUMENT_ID);

        service.recoverRejectedGeneration(
                OWNER, rejected.operationId());
        verify(downstream, times(1)).replayRejectedGeneration(
                eq(OWNER), eq(rejected.operationId()), anyMap());
        verify(downstream, times(1))
                .reserveRetainedResponseRecovery(
                        OWNER, rejected.operationId(), 37_798);
    }

    @Test
    void retainedResponseDeadlineRecoveryRenewsAndResumesWithoutAnotherProviderCall() {
        doThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unprocessable Entity",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .generate(anyString(), any(), anyMap());
        var rejected = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "retained-response-deadline-recovery-1",
                selectionRequest());

        UUID recoveryReservation = UUID.fromString(
                "20000000-0000-0000-0000-000000000098");
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("outcome", "ACCEPTED");
        replay.put("providerInvocationCount", 0);
        replay.put("diagnostic", null);
        Map<String, Object> draft = new LinkedHashMap<>(
                generated(rejected.operationId()));
        draft.put("usage", Map.of(
                "inputTokens", 30_945,
                "outputTokens", 6_853,
                "totalTokens", 37_798));
        replay.put("draft", draft);
        when(downstream.replayRejectedGeneration(
                eq(OWNER), eq(rejected.operationId()), anyMap()))
                .thenReturn(replay);
        when(downstream.reserveRetainedResponseRecovery(
                OWNER, rejected.operationId(), 37_798))
                .thenReturn(Map.of(
                        "reservationId",
                        recoveryReservation.toString(),
                        "status", "RESERVED"));
        doAnswer(invocation -> {
                    jdbc.update("""
                            UPDATE generation_operations
                               SET deadline_at = ?
                             WHERE id = ? AND owner_id = ?
                            """,
                            OffsetDateTime.ofInstant(
                                    Instant.now().minusSeconds(1),
                                    ZoneOffset.UTC),
                            rejected.operationId(),
                            OWNER);
                    return null;
                })
                .when(downstream)
                .commit(OWNER, recoveryReservation, 37_798);

        AtomicReference<Runnable> initialWork = new AtomicReference<>();
        reset(workScheduler);
        doAnswer(invocation -> {
                    initialWork.set(
                            invocation.getArgument(2, Runnable.class));
                    return true;
                })
                .when(workScheduler)
                .submit(any(), anyString(), any(Runnable.class));

        service.recoverRejectedGeneration(
                OWNER, rejected.operationId());
        assertNotNull(initialWork.get());
        initialWork.get().run();
        var deadlineRecovery = service.get(
                OWNER, rejected.operationId());
        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                deadlineRecovery.state());
        assertEquals(
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                deadlineRecovery.failureCode());

        AtomicReference<Runnable> resumedWork = new AtomicReference<>();
        reset(workScheduler);
        doAnswer(invocation -> {
                    resumedWork.set(
                            invocation.getArgument(2, Runnable.class));
                    return true;
                })
                .when(workScheduler)
                .submit(any(), anyString(), any(Runnable.class));

        var prepared = service.recoverRejectedGeneration(
                OWNER, rejected.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                prepared.state());
        assertNull(prepared.failureCode());
        assertTrue(prepared.deadlineAt().isAfter(Instant.now()));
        assertNotNull(resumedWork.get());

        resumedWork.get().run();
        var completed = service.get(
                OWNER, rejected.operationId());
        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        verify(downstream, times(1)).generate(
                anyString(), any(), anyMap());
        verify(downstream, times(1)).replayRejectedGeneration(
                eq(OWNER), eq(rejected.operationId()), anyMap());
        verify(downstream, times(1))
                .reserveRetainedResponseRecovery(
                        OWNER, rejected.operationId(), 37_798);
        verify(downstream, times(1)).commit(
                OWNER, recoveryReservation, 37_798);
    }

    @Test
    void retainedResponseOperatorRecoveryResumesApplicationLinkConflict() {
        doThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unprocessable Entity",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .generate(anyString(), any(), anyMap());
        var rejected = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "retained-application-link-recovery-1",
                selectionRequest());

        UUID recoveryReservation = UUID.fromString(
                "20000000-0000-0000-0000-000000000097");
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("outcome", "ACCEPTED");
        replay.put("providerInvocationCount", 0);
        replay.put("diagnostic", null);
        Map<String, Object> draft = new LinkedHashMap<>(
                generated(rejected.operationId()));
        draft.put("usage", Map.of(
                "inputTokens", 30_945,
                "outputTokens", 6_853,
                "totalTokens", 37_798));
        replay.put("draft", draft);
        when(downstream.replayRejectedGeneration(
                eq(OWNER), eq(rejected.operationId()), anyMap()))
                .thenReturn(replay);
        when(downstream.reserveRetainedResponseRecovery(
                OWNER, rejected.operationId(), 37_798))
                .thenReturn(Map.of(
                        "reservationId",
                        recoveryReservation.toString(),
                        "status", "RESERVED"));
        HttpClientErrorException applicationConflict =
                HttpClientErrorException.create(
                        HttpStatus.CONFLICT,
                        "Conflict",
                        org.springframework.http.HttpHeaders.EMPTY,
                        """
                        {"status":409,"message":"An application for this canonical job is already tracked for the owner."}
                        """.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8);
        doThrow(applicationConflict)
                .when(downstream)
                .createApplication(
                        anyString(), anyString(), anyMap());
        when(downstream.applications(OWNER))
                .thenThrow(applicationConflict);

        service.recoverRejectedGeneration(
                OWNER, rejected.operationId());
        var linkRecovery = service.get(
                OWNER, rejected.operationId());
        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                linkRecovery.state());
        assertEquals(
                "APPLICATION_LINK_RECOVERY_REQUIRED",
                linkRecovery.failureCode());

        reset(downstream);
        doThrow(applicationConflict)
                .when(downstream)
                .createApplication(
                        anyString(), anyString(), anyMap());
        when(downstream.applications(OWNER)).thenReturn(List.of(
                Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "DOCUMENTS_GENERATED",
                        "cvDocumentId", UUID.randomUUID().toString(),
                        "coverLetterDocumentId",
                                UUID.randomUUID().toString(),
                        "version", 3)));
        when(downstream.updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                rejected.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "status", "DOCUMENTS_GENERATED",
                        "version", 4));

        service.recoverRejectedGeneration(
                OWNER, rejected.operationId());
        var completed = service.get(
                OWNER, rejected.operationId());
        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        assertEquals(APPLICATION_ID, completed.applicationId());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());
        verify(downstream, never())
                .reserveRetainedResponseRecovery(
                        anyString(), any(), anyLong());
        verify(downstream, never()).commit(
                anyString(), any(), anyLong());
        verify(downstream).updateApplicationDocumentSelections(
                OWNER,
                APPLICATION_ID,
                rejected.operationId()
                        + ":application-document-selections",
                3,
                CV_DOCUMENT_ID,
                COVER_LETTER_DOCUMENT_ID);
    }

    @Test
    void releasesCreditWhenTheSuccessfulResponseContractIsInvalid() {
        doReturn(Map.of("unexpected", "response"))
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var rejected = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "invalid-generation-response-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.FAILED,
                rejected.state());
        assertEquals(
                "INVALID_GENERATION_RESPONSE",
                rejected.failureCode());
        verify(downstream).release(
                OWNER,
                RESERVATION_ID,
                "INVALID_GENERATION_RESPONSE");
        verify(downstream, never()).commit(
                anyString(), any(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptsAnExplicitlyBlankOptionalClaimReview() {
        doAnswer(invocation -> {
                    Map<String, Object> response = new LinkedHashMap<>(
                            generated(invocation.getArgument(1)));
                    Map<String, Object> ledger = new LinkedHashMap<>(
                            (Map<String, Object>) response.get(
                                    "claimLedger"));
                    List<Map<String, Object>> claims = new ArrayList<>(
                            (List<Map<String, Object>>) ledger.get(
                                    "claims"));
                    Map<String, Object> claim = new LinkedHashMap<>(
                            claims.get(0));
                    claim.put("reviewText", "");
                    claims.set(0, claim);
                    ledger.put("claims", claims);
                    response.put("claimLedger", ledger);
                    return response;
                })
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var accepted = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "blank-claim-review-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                accepted.state());
        verify(downstream, times(2)).createDocument(
                anyString(), anyString(), anyMap());
    }

    @Test
    void timedOutExportResumesWithTheSameReplayKey() {
        var awaiting = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "export-failure-1",
                selectionRequest());
        doThrow(new ResourceAccessException(
                        "connection closed after export"))
                .when(downstream)
                .exportDocument(anyString(), any(), anyString(), anyMap());

        var retryable = approveAndAwait(
                OWNER,
                awaiting.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.CV_EXPORT_IN_PROGRESS,
                retryable.state());
        assertEquals(
                "DOWNSTREAM_RETRYABLE",
                retryable.failureCode());
        assertFalse(retryable.manualActionRequired());

        doAnswer(invocation -> Map.of(
                        "documentId",
                        invocation.getArgument(1).toString(),
                        "exports", List.of(
                                Map.of("format", "DOCX"),
                                Map.of("format", "PDF"))))
                .when(downstream)
                .exportDocument(anyString(), any(), anyString(), anyMap());
        var completed = approveAndAwait(
                OWNER,
                awaiting.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        verify(downstream, times(2)).exportDocument(
                eq(OWNER),
                eq(CV_DOCUMENT_ID),
                eq(awaiting.operationId() + ":cv-export"),
                anyMap());
    }

    @Test
    void cvAndCoverLetterExportsUseDistinctDurableReplayKeys() {
        var awaiting = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "distinct-export-keys-1",
                selectionRequest());

        var completed = approveAndAwait(
                OWNER,
                awaiting.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        ArgumentCaptor<UUID> documentIds =
                ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> replayKeys =
                ArgumentCaptor.forClass(String.class);
        verify(downstream, times(2)).exportDocument(
                eq(OWNER),
                documentIds.capture(),
                replayKeys.capture(),
                anyMap());
        assertEquals(
                List.of(CV_DOCUMENT_ID, COVER_LETTER_DOCUMENT_ID),
                documentIds.getAllValues());
        assertEquals(
                List.of(
                        awaiting.operationId() + ":cv-export",
                        awaiting.operationId() + ":cover-letter-export"),
                replayKeys.getAllValues());
    }

    @Test
    void rejectsExpiredSavedJobBeforeEstimateOrCreditReservation() {
        when(downstream.savedJob(
                SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJobResponse("EXPIRED_SNAPSHOT"));

        var failed = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "expired-job-1",
                selectionRequest());

        assertEquals(GenerationOperationState.CREATED, failed.state());
        assertEquals(
                "SAVED_JOB_SNAPSHOT_EXPIRED",
                failed.failureCode());
        verify(downstream, never()).profile();
        verify(downstream, never()).estimate(anyString(), anyMap());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        doReturn(savedJobResponse("SNAPSHOT"))
                .when(downstream)
                .savedJob(SAVED_JOB_ID, AUTHORIZATION);
        var refreshed = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "expired-job-1",
                selectionRequest());
        assertEquals(failed.operationId(), refreshed.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                refreshed.state());
        verify(downstream, times(1)).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, times(1)).generate(
                anyString(), any(), anyMap());
    }

    @Test
    void missingSavedJobProducesStableFailureBeforeAnyCharge() {
        doThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND,
                        "saved job not found",
                        null,
                        null,
                        null))
                .when(downstream)
                .savedJob(SAVED_JOB_ID, AUTHORIZATION);

        var failed = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "missing-job-1",
                selectionRequest());

        assertEquals(GenerationOperationState.CREATED, failed.state());
        assertEquals(
                "SAVED_JOB_NOT_AVAILABLE",
                failed.failureCode());
        verify(downstream, never()).profile();
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        doReturn(savedJobResponse("SNAPSHOT"))
                .when(downstream)
                .savedJob(SAVED_JOB_ID, AUTHORIZATION);
        var nowAvailable = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "missing-job-1",
                selectionRequest());
        assertEquals(failed.operationId(), nowAvailable.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                nowAvailable.state());
    }

    @Test
    void totalDeadlineBeforeProviderCanBeReplayedWithoutDuplicateCharge() {
        MutableClock clock = new MutableClock(Instant.now());
        AtomicInteger snapshotAttempts = new AtomicInteger();
        var shortDeadlineService = new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                new OperationDeadlineGuard(clock),
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(500));
        when(downstream.savedJob(
                SAVED_JOB_ID, AUTHORIZATION))
                .thenAnswer(invocation -> {
                    if (snapshotAttempts.getAndIncrement() == 0) {
                        clock.advance(Duration.ofMinutes(2));
                    }
                    return savedJobResponse("SNAPSHOT");
                });

        var failed = startAndAwait(
                shortDeadlineService,
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "slow-snapshot-1",
                selectionRequest());

        assertEquals(GenerationOperationState.FAILED, failed.state());
        assertEquals(
                "OPERATION_DEADLINE_EXCEEDED",
                failed.failureCode());
        verify(downstream, never()).profile();
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        clock.advance(Duration.ofMinutes(-2));
        var recovered = startAndAwait(
                shortDeadlineService,
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "slow-snapshot-1",
                selectionRequest());

        assertEquals(failed.operationId(), recovered.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                recovered.state());
        assertNull(recovered.failureCode());
        verify(downstream, times(1)).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, times(1)).generate(
                anyString(), any(), anyMap());
    }

    @Test
    void providerDeadlineIsAmbiguousAndNeverAutomaticallyRetried() {
        MutableClock clock = new MutableClock(Instant.now());
        var shortDeadlineService = new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                new OperationDeadlineGuard(clock),
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(500));
        doAnswer(invocation -> {
                    clock.advance(Duration.ofMinutes(2));
                    return generated(invocation.getArgument(1));
                })
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var unknown = startAndAwait(
                shortDeadlineService,
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "slow-provider-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.GENERATION_OUTCOME_UNKNOWN,
                unknown.state());
        assertEquals(
                "GENERATION_OUTCOME_UNKNOWN",
                unknown.failureCode());
        startAndAwait(
                shortDeadlineService,
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "slow-provider-1",
                selectionRequest());
        verify(downstream, times(1))
                .generate(anyString(), any(), anyMap());
        verify(downstream, never()).commit(
                anyString(), any(), anyLong());
    }

    @Test
    void ambiguousCreditReservationAtDeadlineRequiresRecovery() {
        MutableClock clock = new MutableClock(Instant.now());
        var shortDeadlineService = new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                new OperationDeadlineGuard(clock),
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(500));
        doAnswer(invocation -> {
                    clock.advance(Duration.ofMinutes(2));
                    return Map.of(
                            "reservationId",
                            RESERVATION_ID.toString(),
                            "status",
                            "RESERVED");
                })
                .when(downstream)
                .reserve(anyString(), any(), anyLong(), anyBoolean());

        var recovery = startAndAwait(
                shortDeadlineService,
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "slow-reserve-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                recovery.state());
        assertEquals(
                "CREDIT_RESERVATION_RECOVERY_REQUIRED",
                recovery.failureCode());
        assertTrue(recovery.manualActionRequired());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());
        verify(downstream, never()).commit(
                anyString(), any(), anyLong());
    }

    @Test
    void stopsBeforeCreditReservationWhenTheSavedJobContainsOnlyAPreview() {
        Map<String, Object> savedJob = new LinkedHashMap<>(
                savedJobResponse("SNAPSHOT"));
        Map<String, Object> job = new LinkedHashMap<>(
                (Map<String, Object>) savedJob.get("job"));
        job.put("description", "Short provider preview...");
        job.put("descriptionCompleteness", "PREVIEW");
        savedJob.put("job", job);
        when(downstream.savedJob(SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJob);

        var result = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "preview-job-1",
                selectionRequest());

        assertEquals(GenerationOperationState.CREATED, result.state());
        assertEquals(
                "JOB_DESCRIPTION_REVIEW_REQUIRED",
                result.failureCode());
        verify(downstream, never()).estimate(anyString(), anyMap());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(anyString(), any(), anyMap());
    }

    @Test
    void stopsBeforeCreditReservationWhenAdvertCompletenessIsUnknown() {
        Map<String, Object> savedJob = new LinkedHashMap<>(
                savedJobResponse("SNAPSHOT"));
        Map<String, Object> job = new LinkedHashMap<>(
                (Map<String, Object>) savedJob.get("job"));
        job.remove("descriptionCompleteness");
        savedJob.put("job", job);
        when(downstream.savedJob(SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJob);

        var result = startAndAwait(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "unknown-job-1",
                selectionRequest());

        assertEquals(GenerationOperationState.CREATED, result.state());
        assertEquals(
                "JOB_DESCRIPTION_REVIEW_REQUIRED",
                result.failureCode());
        verify(downstream, never()).estimate(anyString(), anyMap());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(anyString(), any(), anyMap());
    }

    @Test
    void cancellationAfterReplaySafeFailureIsPersistedAndDeterministic() {
        doThrow(new ResourceAccessException(
                        "estimate temporarily unavailable"))
                .when(downstream)
                .estimate(anyString(), anyMap());

        var retryable = startAndAwait(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "cancel-1",
                selectionRequest());
        assertEquals(
                GenerationOperationState.SNAPSHOTS_RESOLVED,
                retryable.state());
        assertEquals(
                "DOWNSTREAM_RETRYABLE",
                retryable.failureCode());

        var cancelled =
                service.cancel(OWNER, retryable.operationId());
        assertEquals(
                GenerationOperationState.CANCELLED,
                cancelled.state());
        assertEquals(
                GenerationOperationState.CANCELLED,
                startAndAwait(
                        OWNER,
                        AUTHORIZATION,
                        SAVED_JOB_ID,
                        "cancel-1",
                        selectionRequest()).state());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong(), anyBoolean());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());
    }

    private void successfulDownstream() {
        when(downstream.savedJob(
                any(), anyString())).thenReturn(
                        savedJobResponse("SNAPSHOT"));
        when(downstream.profile()).thenReturn(Map.of(
                "id", 42,
                "userId", OWNER,
                "revisionId",
                "60000000-0000-4000-8000-000000000001",
                "contentDigest", "f".repeat(64),
                "skills", List.of("Java", "PostgreSQL"),
                "aspirations", Map.of(
                        "targetRoles",
                        List.of("Backend Developer")),
                "professionalContact", Map.of(
                        "phone", "+44 20 7946 0958",
                        "links", List.of(Map.of(
                                "label", "GitHub",
                                "url", "https://github.com/example"))),
                "qualifications", List.of(Map.of(
                        "qualificationName", "BSc Computing",
                        "status", "COMPLETED")),
                "roles", List.of(Map.of(
                        "jobTitle", "Developer",
                        "employer", "Previous Ltd",
                        "startDate", "2022-01-01",
                        "status", "CURRENT"))));
        when(downstream.evidenceSnapshot(
                any(DocumentEvidenceSelection.class)))
                .thenAnswer(invocation -> evidenceSnapshot(
                        invocation.getArgument(0)));
        when(downstream.account(anyString())).thenReturn(Map.of(
                "name", "Alex Candidate",
                "email", "alex@example.com"));
        when(downstream.estimate(anyString(), anyMap()))
                .thenReturn(1000L);
        when(downstream.estimateSelected(
                anyString(), any(DocumentPurpose.class), anyMap()))
                .thenReturn(300L);
        when(downstream.reserve(
                anyString(), any(), anyLong(), anyBoolean()))
                .thenReturn(Map.of(
                        "reservationId",
                        RESERVATION_ID.toString(),
                        "status", "RESERVED"));
        when(downstream.reserveSelected(
                anyString(), any(), any(DocumentPurpose.class), anyLong(),
                anyBoolean()))
                .thenAnswer(invocation -> Map.of(
                        "reservationId",
                        (invocation.getArgument(2) == DocumentPurpose.CV
                                ? RESERVATION_ID
                                : COVER_LETTER_RESERVATION_ID).toString(),
                        "status", "RESERVED"));
        when(downstream.generate(
                anyString(), any(), anyMap()))
                .thenAnswer(invocation ->
                        generated(invocation.getArgument(1)));
        when(downstream.generateSelected(
                anyString(), any(), any(DocumentPurpose.class), anyMap()))
                .thenAnswer(invocation -> selectedGenerated(
                        invocation.getArgument(1),
                        invocation.getArgument(2)));
        when(downstream.createDocument(
                anyString(), anyString(), anyMap()))
                .thenAnswer(invocation ->
                        documentResponse(invocation.getArgument(2)));
        when(downstream.approveDocument(
                anyString(), any())).thenAnswer(invocation -> Map.of(
                "id", invocation.getArgument(1).toString(),
                "lifecycleState", "APPROVED"));
        when(downstream.exportDocument(
                anyString(), any(), anyString(), anyMap())).thenAnswer(invocation -> Map.of(
                "documentId",
                invocation.getArgument(1).toString(),
                "exports", List.of(
                        Map.of("format", "DOCX"),
                        Map.of("format", "PDF"))));
        when(downstream.createApplication(
                anyString(), anyString(), anyMap()))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "status", "DOCUMENTS_GENERATED"));
    }

    private void stubSavedApplicationForSelectiveApproval() {
        when(downstream.createApplication(
                anyString(), anyString(), anyMap()))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 1));
        when(downstream.applications(OWNER)).thenReturn(List.of(Map.of(
                "id", APPLICATION_ID.toString(),
                "canonicalJobId", "canonical-job-1",
                "status", "SAVED",
                "version", 1)));
        when(downstream.updateApplicationDocumentSelections(
                eq(OWNER),
                eq(APPLICATION_ID),
                anyString(),
                eq(1L),
                org.mockito.ArgumentMatchers.nullable(UUID.class),
                org.mockito.ArgumentMatchers.nullable(UUID.class)))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 2));
    }

    private GenerationOperationResponse startAndAwait(
            String ownerId,
            String authorization,
            UUID savedJobId,
            String idempotencyKey,
            StartGenerationRequest request) {
        return startAndAwait(
                service,
                ownerId,
                authorization,
                savedJobId,
                idempotencyKey,
                request);
    }

    private GenerationOperationResponse startAndAwait(
            DurableGenerationService generationService,
            String ownerId,
            String authorization,
            UUID savedJobId,
            String idempotencyKey,
            StartGenerationRequest request) {
        GenerationOperationResponse accepted = generationService.start(
                ownerId,
                authorization,
                savedJobId,
                idempotencyKey,
                request);
        return generationService.get(ownerId, accepted.operationId());
    }

    private GenerationOperationResponse approveAndAwait(
            String ownerId,
            UUID operationId,
            ApproveGenerationRequest request) {
        GenerationOperationResponse accepted = service.approve(
                ownerId,
                operationId,
                request);
        return service.get(ownerId, accepted.operationId());
    }

    private Map<String, Object> savedJobResponse(
            String sourceState) {
        return savedJobResponse(sourceState, "2026-07-01");
    }

    private Map<String, Object> savedJobResponse(
            String sourceState,
            String postedDate) {
        return Map.of(
                "savedJobId", SAVED_JOB_ID.toString(),
                "canonicalJobId", "canonical-job-1",
                "canonicalSchemaVersion", "2.0",
                "snapshotVersion", 3,
                "contentVersion", "sha256:"
                        + "a".repeat(64),
                "contentSha256", "a".repeat(64),
                "sourceState", sourceState,
                "job", Map.ofEntries(
                        Map.entry("id", "provider-job-1"),
                        Map.entry("canonicalJobId", "canonical-job-1"),
                        Map.entry("provider", "REED"),
                        Map.entry("externalJobId", "reed-1"),
                        Map.entry("title", "Java Developer"),
                        Map.entry("company", "Example Ltd"),
                        Map.entry("advertiserName", "Example Ltd"),
                        Map.entry("advertiserType", "EMPLOYER"),
                        Map.entry("location", "London"),
                        Map.entry("employmentType", "FULL_TIME"),
                        Map.entry("postedDate", postedDate),
                        Map.entry(
                                "description",
                                "Build and launch reliable production services with a collaborative product team. "
                                        .repeat(10)),
                        Map.entry(
                                "descriptionCompleteness",
                                "USER_CONFIRMED")));
    }

    private Map<String, Object> generated(UUID operationId) {
        return Map.of(
                "operationId", operationId.toString(),
                "inputSchemaVersion", "2.0",
                "cvTitle", "Tailored CV",
                "cvContent", "CV content",
                "coverLetterTitle", "Cover letter",
                "coverLetterContent", "Letter content",
                "generationMetadata", Map.of(
                        "releaseId", "release-1"),
                "usage", Map.of(
                        "inputTokens", 400,
                        "outputTokens", 200,
                        "totalTokens", 600),
                "claimLedger", Map.of(
                        "ledgerId",
                        "a0000000-0000-4000-8000-000000000001",
                        "ledgerSha256", "c".repeat(64),
                        "policyVersion", "2.0.0",
                        "parserVersion", "3.0.0",
                        "claims", List.of(
                                Map.of(
                                        "claimId", "CLAIM-001",
                                        "disposition", "SUPPORTED",
                                        "evidenceIds", List.of(
                                                "80000000-0000-4000-8000-000000000001"),
                                        "contentPaths", List.of(
                                                "cv.experience[0]"),
                                        "reviewText",
                                        "Grounded CV claim"),
                                Map.of(
                                        "claimId", "CLAIM-002",
                                        "disposition", "REWORDED",
                                        "evidenceIds", List.of(
                                                "80000000-0000-4000-8000-000000000002"),
                                        "contentPaths", List.of(
                                                "coverLetter.paragraphs[1]"),
                                        "reviewText",
                                        "Grounded cover-letter claim"))),
                "audit", Map.of("modelId", "fixture"));
    }

    private Map<String, Object> selectedGenerated(
            UUID operationId,
            DocumentPurpose output) {
        return Map.ofEntries(
                Map.entry("operationId", operationId.toString()),
                Map.entry("outputType", output.name()),
                Map.entry("inputSchemaVersion", "2.0"),
                Map.entry("title", output == DocumentPurpose.CV
                        ? "Tailored CV"
                        : "Cover letter"),
                Map.entry("content", output == DocumentPurpose.CV
                        ? "CV content"
                        : "Letter content"),
                Map.entry("generationMetadata", Map.of(
                        "releaseId", "release-1")),
                Map.entry("usage", Map.of(
                        "inputTokens", 200,
                        "outputTokens", 100,
                        "totalTokens", 300)),
                Map.entry("claimLedger", Map.of(
                        "ledgerId",
                        "a0000000-0000-4000-8000-000000000001",
                        "ledgerSha256", "c".repeat(64),
                        "policyVersion", "2.0.0",
                        "parserVersion", "3.0.0",
                        "claims", List.of(Map.of(
                                "claimId", "CLAIM-001",
                                "disposition", "SUPPORTED",
                                "evidenceIds", List.of(output
                                        == DocumentPurpose.CV
                                                ? "80000000-0000-4000-8000-000000000001"
                                                : "80000000-0000-4000-8000-000000000002"),
                                "contentPaths", List.of(output
                                        == DocumentPurpose.CV
                                                ? "cv.experience[0]"
                                                : "coverLetter.paragraphs[1]"),
                                "reviewText", "Grounded claim")))),
                Map.entry("audit", Map.of(
                        "modelId", "fixture",
                        "providerAttemptCount", 2,
                        "automaticRetryCount", 1,
                        "retryReason", "RATE_LIMITED")),
                Map.entry("recovery", Map.ofEntries(
                        Map.entry("finalSource", "LLM"),
                        Map.entry("structuralRepairAttempted", true),
                        Map.entry("structuralRepairSucceeded", true),
                        Map.entry("duplicateItemsRemoved", 1),
                        Map.entry("retainedResponseReplay", false),
                        Map.entry("fallbackUsed", false),
                        Map.entry("fallbackVersion", "none"),
                        Map.entry("providerAttemptCount", 2),
                        Map.entry("automaticRetryCount", 1),
                        Map.entry("retried", true),
                        Map.entry("retryReason", "RATE_LIMITED"))));
    }

    private StartGenerationRequest selectionRequest() {
        return selectionRequest(
                CV_EVIDENCE_ID,
                COVER_LETTER_EVIDENCE_ID);
    }

    private StartGenerationRequest cvOnlyRequest() {
        return new StartGenerationRequest(
                Set.of(DocumentPurpose.CV),
                List.of(new DocumentEvidenceSelection(
                        DocumentPurpose.CV,
                        List.of(CV_EVIDENCE_ID),
                        List.of(EvidenceSection.PROJECT))));
    }

    private DurableGenerationService recoveryEnabledService(
            int maxAttempts) {
        return new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                deadlineGuard,
                workScheduler,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                true,
                Duration.ofMillis(1),
                maxAttempts);
    }

    private StartGenerationRequest selectionRequest(
            UUID cvEvidenceId,
            UUID coverLetterEvidenceId) {
        return new StartGenerationRequest(List.of(
                new DocumentEvidenceSelection(
                        DocumentPurpose.CV,
                        List.of(cvEvidenceId),
                        List.of(EvidenceSection.PROJECT)),
                new DocumentEvidenceSelection(
                        DocumentPurpose.COVER_LETTER,
                        List.of(coverLetterEvidenceId),
                        List.of(EvidenceSection.VOLUNTEERING))));
    }

    private Map<String, Object> evidenceSnapshot(
            DocumentEvidenceSelection requested) {
        boolean cv = requested.purpose() == DocumentPurpose.CV;
        List<Map<String, Object>> selections = new ArrayList<>();
        for (UUID entryId : requested.entryIds()) {
            selections.add(Map.of(
                    "entryId", entryId.toString(),
                    "revisionId", cv
                            ? "70000000-0000-4000-8000-000000000001"
                            : "70000000-0000-4000-8000-000000000002",
                    "revisionNumber", 2,
                    "category", requested.sectionOrder().get(0).name(),
                    "contentDigest", "e".repeat(64),
                    "facts", List.of(Map.of(
                            "factId", cv
                                    ? "80000000-0000-4000-8000-000000000001"
                                    : "80000000-0000-4000-8000-000000000002",
                            "factType", "DESCRIPTION",
                            "factValue", cv
                                    ? "Built a community scheduling tool."
                                    : "Volunteered as a careers mentor.",
                            "numericClaim", false))));
        }
        return Map.of(
                "snapshotId", cv
                        ? "90000000-0000-4000-8000-000000000001"
                        : "90000000-0000-4000-8000-000000000002",
                "purpose", requested.purpose().name(),
                "profileRevisionId",
                "60000000-0000-4000-8000-000000000001",
                "profileContentDigest", "f".repeat(64),
                "snapshotDigest", cv
                        ? "a".repeat(64)
                        : "b".repeat(64),
                "sectionOrder", requested.sectionOrder().stream()
                        .map(Enum::name)
                        .toList(),
                "selections", selections);
    }

    private Map<String, Object> documentResponse(
            Map<String, Object> request) {
        boolean cv = "CV".equals(request.get("documentType"));
        return Map.of(
                "id",
                (cv ? CV_DOCUMENT_ID
                        : COVER_LETTER_DOCUMENT_ID).toString(),
                "documentType", request.get("documentType"),
                "lifecycleState", "DRAFT",
                "contentSha256", "b".repeat(64));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
