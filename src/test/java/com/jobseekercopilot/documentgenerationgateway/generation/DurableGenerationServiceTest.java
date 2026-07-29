package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentgenerationgateway.dto.ApproveGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentPurpose;
import com.jobseekercopilot.documentgenerationgateway.dto.EvidenceSection;
import com.jobseekercopilot.documentgenerationgateway.dto.StartGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationConflictException;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM generation_operations");
        reset(downstream);
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
        var first = service.start(
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

        var replay = service.start(
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
                () -> service.approve(
                        OWNER,
                        first.operationId(),
                        new ApproveGenerationRequest(
                                UUID.randomUUID(),
                                COVER_LETTER_DOCUMENT_ID)));

        var completed = service.approve(
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
    void replayRecoversDuplicateCanonicalApplicationWithoutRegeneration() {
        var generated = service.start(
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

        var recoveryRequired = service.approve(
                OWNER,
                generated.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                recoveryRequired.state());
        assertEquals(
                "APPROVAL_REQUEST_REJECTED",
                recoveryRequired.failureCode());

        when(downstream.applications(OWNER)).thenReturn(List.of(
                Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 3)));
        when(downstream.updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "CV",
                CV_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 4));
        when(downstream.updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "COVER_LETTER",
                COVER_LETTER_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "SAVED",
                        "version", 5));
        when(downstream.updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                5))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "canonicalJobId", "canonical-job-1",
                        "status", "DOCUMENTS_GENERATED",
                        "version", 6));

        var recovered = service.start(
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
        verify(downstream).updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "CV",
                CV_DOCUMENT_ID);
        verify(downstream).updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "COVER_LETTER",
                COVER_LETTER_DOCUMENT_ID);
        verify(downstream).updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                5);
    }

    @Test
    void retryAcceptsMatchingDocumentsWhenStatusUpdateOutcomeWasUnknown() {
        String idempotencyKey = "ambiguous-status-update-1";
        var generated = service.start(
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
        when(downstream.updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "CV",
                CV_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "status", "SAVED",
                        "version", 4));
        when(downstream.updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "COVER_LETTER",
                COVER_LETTER_DOCUMENT_ID))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "status", "SAVED",
                        "version", 5));
        when(downstream.updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                5))
                .thenThrow(new ResourceAccessException(
                        "status response was lost"));

        var ambiguous = service.approve(
                OWNER,
                generated.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.EXPORTED,
                ambiguous.state());
        assertEquals("DOWNSTREAM_RETRYABLE", ambiguous.failureCode());

        var recovered = service.approve(
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
        verify(downstream, times(1)).updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "CV",
                CV_DOCUMENT_ID);
        verify(downstream, times(1)).updateApplicationDocument(
                OWNER,
                APPLICATION_ID,
                "COVER_LETTER",
                COVER_LETTER_DOCUMENT_ID);
        verify(downstream, times(1)).updateApplicationStatus(
                OWNER,
                APPLICATION_ID,
                "DOCUMENTS_GENERATED",
                5);
    }

    @Test
    void sendsOnlyPurposeSpecificImmutableEvidenceSnapshotsToGeneration() {
        service.start(
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
        assertEquals(List.of(), profile.get("skills"));
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
    void idempotencyKeyCannotBeReusedForDifferentEvidenceSelection() {
        service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selection-conflict-1",
                selectionRequest());

        assertThrows(
                GenerationConflictException.class,
                () -> service.start(
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
        var first = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "selection-a",
                selectionRequest());
        var second = service.start(
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

        var operation = service.start(
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
            var first = executor.submit(() -> service.start(
                    OWNER,
                    AUTHORIZATION,
                    SAVED_JOB_ID,
                    "concurrent-key-a",
                    selectionRequest()));
            assertTrue(generationEntered.await(
                    5, TimeUnit.SECONDS));
            var duplicate = executor.submit(() -> service.start(
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
                service.start(
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

        var unknown = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "ambiguous-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.GENERATION_OUTCOME_UNKNOWN,
                unknown.state());
        assertTrue(unknown.manualActionRequired());
        assertEquals(
                "GENERATION_OUTCOME_UNKNOWN",
                unknown.failureCode());

        var replay = service.start(
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
    void replaySafeStoreFailureResumesWithTheSameKeys() {
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

        var interrupted = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "store-retry-1",
                selectionRequest());
        assertEquals(
                GenerationOperationState.CREDIT_COMMITTED,
                interrupted.state());
        assertEquals(
                "DOWNSTREAM_RETRYABLE",
                interrupted.failureCode());

        var resumed = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "store-retry-2",
                selectionRequest());
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

        var rejected = service.start(
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
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8))
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var rejected = service.start(
                OWNER,
                AUTHORIZATION,
                SAVED_JOB_ID,
                "ungrounded-output-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.FAILED,
                rejected.state());
        assertEquals(
                "GENERATION_REJECTED",
                rejected.failureCode());
        verify(downstream).release(
                OWNER,
                RESERVATION_ID,
                "GENERATION_REJECTED");
        verify(downstream, never()).commit(
                anyString(), any(), anyLong());
    }

    @Test
    void releasesCreditWhenTheSuccessfulResponseContractIsInvalid() {
        doReturn(Map.of("unexpected", "response"))
                .when(downstream)
                .generate(anyString(), any(), anyMap());

        var rejected = service.start(
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

        var accepted = service.start(
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
        var awaiting = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "export-failure-1",
                selectionRequest());
        doThrow(new ResourceAccessException(
                        "connection closed after export"))
                .when(downstream)
                .exportDocument(anyString(), any(), anyString());

        var retryable = service.approve(
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
                .exportDocument(anyString(), any(), anyString());
        var completed = service.approve(
                OWNER,
                awaiting.operationId(),
                new ApproveGenerationRequest(
                        CV_DOCUMENT_ID,
                        COVER_LETTER_DOCUMENT_ID));

        assertEquals(
                GenerationOperationState.COMPLETED,
                completed.state());
        verify(downstream, times(2)).exportDocument(
                OWNER,
                CV_DOCUMENT_ID,
                awaiting.operationId() + ":cv-export");
    }

    @Test
    void rejectsExpiredSavedJobBeforeEstimateOrCreditReservation() {
        when(downstream.savedJob(
                SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJobResponse("EXPIRED_SNAPSHOT"));

        var failed = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "expired-job-1",
                selectionRequest());

        assertEquals(GenerationOperationState.CREATED, failed.state());
        assertEquals(
                "SAVED_JOB_SNAPSHOT_EXPIRED",
                failed.failureCode());
        verify(downstream, never()).profile();
        verify(downstream, never()).estimate(anyString(), anyMap());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        doReturn(savedJobResponse("SNAPSHOT"))
                .when(downstream)
                .savedJob(SAVED_JOB_ID, AUTHORIZATION);
        var refreshed = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "expired-job-1",
                selectionRequest());
        assertEquals(failed.operationId(), refreshed.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                refreshed.state());
        verify(downstream, times(1)).reserve(
                anyString(), any(), anyLong());
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

        var failed = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "missing-job-1",
                selectionRequest());

        assertEquals(GenerationOperationState.CREATED, failed.state());
        assertEquals(
                "SAVED_JOB_NOT_AVAILABLE",
                failed.failureCode());
        verify(downstream, never()).profile();
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong());
        verify(downstream, never()).generate(
                anyString(), any(), anyMap());

        doReturn(savedJobResponse("SNAPSHOT"))
                .when(downstream)
                .savedJob(SAVED_JOB_ID, AUTHORIZATION);
        var nowAvailable = service.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "missing-job-1",
                selectionRequest());
        assertEquals(failed.operationId(), nowAvailable.operationId());
        assertEquals(
                GenerationOperationState.AWAITING_APPROVAL,
                nowAvailable.state());
    }

    @Test
    void totalDeadlineStopsSlowSnapshotResolutionBeforeAnyCharge() {
        MutableClock clock = new MutableClock(Instant.now());
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
                    clock.advance(Duration.ofMinutes(2));
                    return savedJobResponse("SNAPSHOT");
                });

        var failed = shortDeadlineService.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "slow-snapshot-1",
                selectionRequest());

        assertEquals(GenerationOperationState.FAILED, failed.state());
        assertEquals(
                "OPERATION_DEADLINE_EXCEEDED",
                failed.failureCode());
        verify(downstream, never()).profile();
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong());
        verify(downstream, never()).generate(
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

        var unknown = shortDeadlineService.start(
                OWNER, AUTHORIZATION, SAVED_JOB_ID, "slow-provider-1",
                selectionRequest());

        assertEquals(
                GenerationOperationState.GENERATION_OUTCOME_UNKNOWN,
                unknown.state());
        assertEquals(
                "GENERATION_OUTCOME_UNKNOWN",
                unknown.failureCode());
        shortDeadlineService.start(
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
                .reserve(anyString(), any(), anyLong());

        var recovery = shortDeadlineService.start(
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
    void cancellationAfterReplaySafeFailureIsPersistedAndDeterministic() {
        doThrow(new ResourceAccessException(
                        "estimate temporarily unavailable"))
                .when(downstream)
                .estimate(anyString(), anyMap());

        var retryable = service.start(
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
                service.start(
                        OWNER,
                        AUTHORIZATION,
                        SAVED_JOB_ID,
                        "cancel-1",
                        selectionRequest()).state());
        verify(downstream, never()).reserve(
                anyString(), any(), anyLong());
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
        when(downstream.reserve(
                anyString(), any(), anyLong()))
                .thenReturn(Map.of(
                        "reservationId",
                        RESERVATION_ID.toString(),
                        "status", "RESERVED"));
        when(downstream.generate(
                anyString(), any(), anyMap()))
                .thenAnswer(invocation ->
                        generated(invocation.getArgument(1)));
        when(downstream.createDocument(
                anyString(), anyString(), anyMap()))
                .thenAnswer(invocation ->
                        documentResponse(invocation.getArgument(2)));
        when(downstream.approveDocument(
                anyString(), any())).thenAnswer(invocation -> Map.of(
                "id", invocation.getArgument(1).toString(),
                "lifecycleState", "APPROVED"));
        when(downstream.exportDocument(
                anyString(), any(), anyString())).thenAnswer(invocation -> Map.of(
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
                "job", Map.of(
                        "id", "provider-job-1",
                        "canonicalJobId", "canonical-job-1",
                        "provider", "REED",
                        "externalJobId", "reed-1",
                        "title", "Java Developer",
                        "company", "Example Ltd",
                        "location", "London",
                        "employmentType", "FULL_TIME",
                        "postedDate", postedDate,
                        "description", "Build reliable services."));
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

    private StartGenerationRequest selectionRequest() {
        return selectionRequest(
                CV_EVIDENCE_ID,
                COVER_LETTER_EVIDENCE_ID);
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
