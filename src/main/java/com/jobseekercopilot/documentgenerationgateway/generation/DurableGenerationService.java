package com.jobseekercopilot.documentgenerationgateway.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.ApproveGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentPurpose;
import com.jobseekercopilot.documentgenerationgateway.dto.GenerationOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.StartGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationConflictException;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationDeadlineExceededException;
import com.jobseekercopilot.documentgenerationgateway.exception.GenerationNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

@Service
public class DurableGenerationService {
    private static final int MAX_LEDGER_CLAIMS = 40;
    private static final int MAX_CLAIM_REFERENCES = 30;
    private static final int MAX_CLAIM_REVIEW_TEXT = 500;
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern CONTENT_VERSION =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CONTENT_SHA256 =
            Pattern.compile("[a-f0-9]{64}");

    private final GenerationOperationRepository repository;
    private final GenerationDownstreamClient downstream;
    private final ObjectMapper objectMapper;
    private final OperationDeadlineGuard deadlineGuard;
    private final Duration deadline;
    private final Duration leaseDuration;

    public DurableGenerationService(
            GenerationOperationRepository repository,
            GenerationDownstreamClient downstream,
            ObjectMapper objectMapper,
            OperationDeadlineGuard deadlineGuard,
            @Value("${document-generation.operation.deadline}") Duration deadline,
            @Value("${document-generation.operation.lease}") Duration leaseDuration,
            @Value("${document-generation.downstream.read-timeout}") Duration readTimeout) {
        this.repository = repository;
        this.downstream = downstream;
        this.objectMapper = objectMapper;
        this.deadlineGuard = deadlineGuard;
        this.deadline = requirePositive(deadline, "operation deadline");
        this.leaseDuration = requirePositive(leaseDuration, "operation lease");
        Duration boundedReadTimeout =
                requirePositive(readTimeout, "downstream read timeout");
        if (this.leaseDuration.compareTo(boundedReadTimeout) <= 0) {
            throw new IllegalStateException(
                    "Document generation operation lease must be longer than "
                            + "the downstream read timeout.");
        }
    }

    public GenerationOperationResponse start(
            String ownerId,
            String authorization,
            UUID savedJobId,
            String idempotencyKey,
            StartGenerationRequest request) {
        requireOwner(ownerId);
        requireAuthorization(authorization);
        requireIdempotencyKey(idempotencyKey);
        validateSelectionRequest(request);
        Map<String, Object> initialData = new LinkedHashMap<>();
        initialData.put(
                "evidenceSelectionRequest",
                objectMapper.convertValue(request, LinkedHashMap.class));
        String requestFingerprint = sha256("generation-v2:"
                + savedJobId
                + ":"
                + sha256Json(request));
        GenerationOperation operation = repository.findLatestReplaySafe(
                        ownerId,
                        savedJobId,
                        requestFingerprint)
                .or(() -> repository
                        .findLatestRecoverableApplicationConflict(
                                ownerId,
                                savedJobId,
                                requestFingerprint)
                        .filter(this::recoverableApplicationConflict))
                .orElseGet(() -> repository.createOrReplay(
                        ownerId,
                        idempotencyKey,
                        savedJobId,
                        requestFingerprint,
                        initialData,
                        deadline));
        boolean recovery = recoverableApplicationConflict(operation);
        if ((operation.state().terminal() && !recovery)
                || operation.state() == GenerationOperationState.AWAITING_APPROVAL) {
            return response(operation);
        }

        UUID leaseToken = UUID.randomUUID();
        if (!repository.tryAcquire(
                operation.id(), ownerId, leaseToken, leaseDuration)) {
            return response(required(operation.id(), ownerId));
        }
        try {
            operation = required(operation.id(), ownerId);
            if (recoverableApplicationConflict(operation)) {
                operation = checkpoint(
                        operation,
                        leaseToken,
                        GenerationOperationState.EXPORTED,
                        operation.data(),
                        null,
                        null);
                operation = advanceApproval(operation, leaseToken);
            } else {
                operation = advanceToApproval(
                        operation, leaseToken, authorization);
            }
            return response(operation);
        } finally {
            repository.release(
                    operation.id(), ownerId, leaseToken);
        }
    }

    public GenerationOperationResponse get(String ownerId, UUID operationId) {
        requireOwner(ownerId);
        return response(required(operationId, ownerId));
    }

    public GenerationOperationResponse approve(
            String ownerId,
            UUID operationId,
            ApproveGenerationRequest request) {
        requireOwner(ownerId);
        GenerationOperation operation = required(operationId, ownerId);
        verifyApprovalRequest(operation, request);
        if (operation.state().terminal()) {
            return response(operation);
        }
        UUID leaseToken = UUID.randomUUID();
        if (!repository.tryAcquire(
                operation.id(), ownerId, leaseToken, leaseDuration)) {
            return response(required(operation.id(), ownerId));
        }
        try {
            operation = required(operation.id(), ownerId);
            operation = advanceApproval(operation, leaseToken);
            return response(operation);
        } finally {
            repository.release(
                    operation.id(), ownerId, leaseToken);
        }
    }

    public GenerationOperationResponse cancel(
            String ownerId,
            UUID operationId) {
        requireOwner(ownerId);
        GenerationOperation operation = required(operationId, ownerId);
        if (operation.state() == GenerationOperationState.CANCELLED) {
            return response(operation);
        }
        if (operation.state() == GenerationOperationState.COMPLETED
                || operation.state() == GenerationOperationState.APPROVED
                || operation.state() == GenerationOperationState.CV_EXPORT_IN_PROGRESS
                || operation.state() == GenerationOperationState.CV_EXPORTED
                || operation.state() == GenerationOperationState.COVER_LETTER_EXPORT_IN_PROGRESS
                || operation.state() == GenerationOperationState.EXPORTED
                || operation.state() == GenerationOperationState.GENERATION_IN_PROGRESS
                || operation.state() == GenerationOperationState.GENERATION_OUTCOME_UNKNOWN) {
            throw new GenerationConflictException(
                    "This generation operation can no longer be cancelled safely.");
        }
        UUID leaseToken = UUID.randomUUID();
        if (!repository.tryAcquire(
                operation.id(), ownerId, leaseToken, leaseDuration)) {
            throw new GenerationConflictException(
                    "Generation operation is currently being processed.");
        }
        try {
            operation = required(operation.id(), ownerId);
            UUID reservationId = uuid(operation.data(), "reservationId");
            if (reservationId != null
                    && beforeGeneration(operation.state())) {
                downstream.release(
                        ownerId,
                        reservationId,
                        "Generation cancelled before provider invocation");
            }
            operation = checkpoint(
                    operation,
                    leaseToken,
                    GenerationOperationState.CANCELLED,
                    operation.data(),
                    null,
                    null);
            return response(operation);
        } finally {
            repository.release(
                    operation.id(), ownerId, leaseToken);
        }
    }

    private GenerationOperation advanceToApproval(
            GenerationOperation operation,
            UUID leaseToken,
            String authorization) {
        try {
            while (true) {
                switch (operation.state()) {
                    case CREATED -> operation = resolveSnapshots(
                            operation, leaseToken, authorization);
                    case SNAPSHOTS_RESOLVED -> operation = estimate(
                            operation, leaseToken);
                    case ESTIMATED -> operation = reserve(
                            operation, leaseToken);
                    case CREDIT_RESERVED -> {
                        if (Instant.now().isAfter(operation.deadlineAt())) {
                            operation = releaseAndFail(
                                    operation,
                                    leaseToken,
                                    "OPERATION_DEADLINE_EXCEEDED",
                                    "The generation deadline expired before model invocation.");
                            return operation;
                        }
                        operation = checkpoint(
                                operation,
                                leaseToken,
                                GenerationOperationState.GENERATION_IN_PROGRESS,
                                operation.data(),
                                null,
                                null);
                        operation = invokeGeneration(operation, leaseToken);
                    }
                    case GENERATION_IN_PROGRESS -> {
                        return checkpoint(
                                operation,
                                leaseToken,
                                GenerationOperationState.GENERATION_OUTCOME_UNKNOWN,
                                operation.data(),
                                "GENERATION_OUTCOME_UNKNOWN",
                                "A previous provider invocation was interrupted; automatic retry is disabled to prevent duplicate cost.");
                    }
                    case DRAFT_GENERATED -> operation = commitCredit(
                            operation, leaseToken);
                    case CREDIT_COMMITTED -> operation = storeDrafts(
                            operation, leaseToken);
                    case DRAFTS_STORED -> {
                        return checkpoint(
                                operation,
                                leaseToken,
                                GenerationOperationState.AWAITING_APPROVAL,
                                operation.data(),
                                null,
                                null);
                    }
                    default -> {
                        return operation;
                    }
                }
            }
        } catch (GenerationDeadlineExceededException exception) {
            return deadlineFailure(operation, leaseToken, exception);
        } catch (GenerationSourceException exception) {
            if (exception.retryable()) {
                return checkpoint(
                        operation,
                        leaseToken,
                        operation.state(),
                        operation.data(),
                        exception.code(),
                        exception.getMessage());
            }
            return releaseAndFail(
                    operation,
                    leaseToken,
                    exception.code(),
                    exception.getMessage());
        } catch (HttpStatusCodeException exception) {
            if (operation.state() == GenerationOperationState.CREATED
                    && exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return checkpoint(
                        operation,
                        leaseToken,
                        GenerationOperationState.CREATED,
                        operation.data(),
                        "SAVED_JOB_NOT_AVAILABLE",
                        "The saved job is not available; save or refresh it and replay this operation.");
            }
            if (exception.getStatusCode().is4xxClientError()
                    && beforeGeneration(operation.state())) {
                return releaseAndFail(
                        operation,
                        leaseToken,
                        "DOWNSTREAM_REQUEST_REJECTED",
                        message(exception));
            }
            return retryableFailure(
                    operation, leaseToken, exception);
        } catch (RestClientException exception) {
            return retryableFailure(
                    operation, leaseToken, exception);
        } catch (RuntimeException exception) {
            return checkpoint(
                    operation,
                    leaseToken,
                    GenerationOperationState.FAILED,
                    operation.data(),
                    "INVALID_DOWNSTREAM_RESPONSE",
                    exception.getMessage());
        }
    }

    private GenerationOperation resolveSnapshots(
            GenerationOperation operation,
            UUID leaseToken,
            String authorization) {
        Map<String, Object> savedJob =
                bounded(operation, () -> downstream.savedJob(
                        operation.savedJobId(), authorization));
        validateSavedJob(operation, savedJob);
        Map<String, Object> rawJob = map(savedJob.get("job"), "saved job");
        Map<String, Object> rawProfile =
                bounded(operation, downstream::profile);
        StartGenerationRequest selectionRequest =
                selectionRequest(operation);
        Map<String, Object> cvEvidenceSnapshot = bounded(
                operation,
                () -> downstream.evidenceSnapshot(selection(
                        selectionRequest, DocumentPurpose.CV)));
        validateEvidenceSnapshot(
                selection(selectionRequest, DocumentPurpose.CV),
                cvEvidenceSnapshot);
        Map<String, Object> coverLetterEvidenceSnapshot = bounded(
                operation,
                () -> downstream.evidenceSnapshot(selection(
                        selectionRequest, DocumentPurpose.COVER_LETTER)));
        validateEvidenceSnapshot(
                selection(selectionRequest, DocumentPurpose.COVER_LETTER),
                coverLetterEvidenceSnapshot);
        validateProfileAndPurposeBindings(
                rawProfile,
                cvEvidenceSnapshot,
                coverLetterEvidenceSnapshot);
        Map<String, Object> account;
        try {
            account = bounded(
                    operation,
                    () -> downstream.account(authorization));
        } catch (RestClientException unavailable) {
            account = Map.of();
        }
        Instant capturedAt = Instant.now();
        Map<String, Object> jobSnapshot =
                jobSnapshot(operation, savedJob, rawJob, capturedAt);
        Map<String, Object> profileSnapshot =
                profileSnapshot(operation, rawProfile, account, capturedAt);
        Map<String, Object> generationRequest = new LinkedHashMap<>();
        generationRequest.put("inputSchemaVersion", "2.0");
        generationRequest.put("profile", profileSnapshot);
        generationRequest.put("job", jobSnapshot);
        generationRequest.put("evidenceSnapshots", Map.of(
                "cv", cvEvidenceSnapshot,
                "coverLetter", coverLetterEvidenceSnapshot));

        Map<String, Object> data = data(operation);
        data.put("savedJob", savedJob);
        data.put("generationRequest", generationRequest);
        data.put("jobSnapshotSha256", sha256Json(jobSnapshot));
        data.put("profileSnapshotSha256", sha256Json(profileSnapshot));
        data.put("cvEvidenceSnapshotId",
                requiredText(cvEvidenceSnapshot, "snapshotId"));
        data.put("cvEvidenceSnapshotDigest",
                requiredText(cvEvidenceSnapshot, "snapshotDigest"));
        data.put("coverLetterEvidenceSnapshotId",
                requiredText(coverLetterEvidenceSnapshot, "snapshotId"));
        data.put("coverLetterEvidenceSnapshotDigest",
                requiredText(coverLetterEvidenceSnapshot, "snapshotDigest"));
        data.put("canonicalJobId", firstText(
                savedJob.get("canonicalJobId"),
                rawJob.get("canonicalJobId"),
                rawJob.get("id")));
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.SNAPSHOTS_RESOLVED,
                data,
                null,
                null);
    }

    private void validateSavedJob(
            GenerationOperation operation,
            Map<String, Object> savedJob) {
        UUID returnedSavedJobId =
                requiredUuid(savedJob, "savedJobId");
        if (!operation.savedJobId().equals(returnedSavedJobId)) {
            throw new GenerationSourceException(
                    "SAVED_JOB_ID_MISMATCH",
                    "Job Service returned a different saved job.",
                    false);
        }
        String sourceState =
                requiredText(savedJob, "sourceState");
        if ("EXPIRED_SNAPSHOT".equals(sourceState)) {
            throw new GenerationSourceException(
                    "SAVED_JOB_SNAPSHOT_EXPIRED",
                    "The saved job snapshot has expired; refresh it and replay this operation.",
                    true);
        }
        if (!"SNAPSHOT".equals(sourceState)) {
            throw new GenerationSourceException(
                    "INVALID_SAVED_JOB_SNAPSHOT",
                    "Job Service returned an unsupported saved-job source state.",
                    false);
        }
        if (!"2.0".equals(
                requiredText(savedJob, "canonicalSchemaVersion"))) {
            throw new GenerationSourceException(
                    "INVALID_SAVED_JOB_SNAPSHOT",
                    "Job Service returned an unsupported canonical job schema.",
                    false);
        }
        if (number(savedJob, "snapshotVersion").longValue() < 1) {
            throw new GenerationSourceException(
                    "INVALID_SAVED_JOB_SNAPSHOT",
                    "Job Service returned an invalid snapshot version.",
                    false);
        }
        String contentVersion =
                requiredText(savedJob, "contentVersion");
        String contentSha256 =
                requiredText(savedJob, "contentSha256");
        if (!CONTENT_VERSION.matcher(contentVersion).matches()
                || !CONTENT_SHA256.matcher(contentSha256).matches()
                || !contentVersion.equals("sha256:" + contentSha256)) {
            throw new GenerationSourceException(
                    "INVALID_SAVED_JOB_SNAPSHOT",
                    "Job Service returned inconsistent snapshot content evidence.",
                    false);
        }
    }

    private GenerationOperation estimate(
            GenerationOperation operation,
            UUID leaseToken) {
        long estimatedTokens = bounded(operation, () -> downstream.estimate(
                operation.ownerId(),
                map(operation.data().get("generationRequest"),
                        "generation request")));
        Map<String, Object> data = data(operation);
        data.put("estimatedTokens", estimatedTokens);
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.ESTIMATED,
                data,
                null,
                null);
    }

    private GenerationOperation reserve(
            GenerationOperation operation,
            UUID leaseToken) {
        Map<String, Object> reservation = bounded(
                operation,
                () -> downstream.reserve(
                        operation.ownerId(),
                        operation.id(),
                        number(operation.data(), "estimatedTokens")
                                .longValue()));
        UUID reservationId = requiredUuid(reservation, "reservationId");
        Map<String, Object> data = data(operation);
        data.put("reservationId", reservationId.toString());
        data.put("reservationEvidence", reservation);
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.CREDIT_RESERVED,
                data,
                null,
                null);
    }

    private GenerationOperation invokeGeneration(
            GenerationOperation operation,
            UUID leaseToken) {
        try {
            Map<String, Object> generated = bounded(
                    operation,
                    () -> downstream.generate(
                            operation.ownerId(),
                            operation.id(),
                            map(operation.data().get("generationRequest"),
                                    "generation request")));
            UUID returnedOperation = requiredUuid(generated, "operationId");
            if (!returnedOperation.equals(operation.id())) {
                throw new IllegalStateException(
                        "CV Service returned a mismatched operation ID.");
            }
            Map<String, Object> usage =
                    map(generated.get("usage"), "generation usage");
            long actualTokens = number(usage, "totalTokens").longValue();
            if (actualTokens < 1) {
                throw new IllegalStateException(
                        "CV Service returned invalid token usage.");
            }
            Map<String, Object> data = data(operation);
            data.put("generation", generated);
            data.put("generationCompletedAt", Instant.now().toString());
            data.put("actualTokens", actualTokens);
            return checkpoint(
                    operation,
                    leaseToken,
                    GenerationOperationState.DRAFT_GENERATED,
                    data,
                    null,
                    null);
        } catch (GenerationDeadlineExceededException exception) {
            return generationOutcomeUnknown(
                    operation, leaseToken, exception);
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                return releaseAndFail(
                        operation,
                        leaseToken,
                        "GENERATION_REJECTED",
                        message(exception));
            }
            return generationOutcomeUnknown(
                    operation, leaseToken, exception);
        } catch (RestClientException exception) {
            return generationOutcomeUnknown(
                    operation, leaseToken, exception);
        } catch (RuntimeException invalidResponse) {
            return releaseAndFail(
                    operation,
                    leaseToken,
                    "INVALID_GENERATION_RESPONSE",
                    "The generated draft response was invalid and no credits were charged.");
        }
    }

    private GenerationOperation commitCredit(
            GenerationOperation operation,
            UUID leaseToken) {
        bounded(operation, () -> downstream.commit(
                operation.ownerId(),
                requiredUuid(operation.data(), "reservationId"),
                number(operation.data(), "actualTokens").longValue()));
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.CREDIT_COMMITTED,
                operation.data(),
                null,
                null);
    }

    private GenerationOperation storeDrafts(
            GenerationOperation operation,
            UUID leaseToken) {
        Map<String, Object> generated =
                map(operation.data().get("generation"), "generation");
        Map<String, Object> savedJob =
                map(operation.data().get("savedJob"), "saved job");
        Map<String, Object> rawJob =
                map(savedJob.get("job"), "saved job");
        String jobId = firstText(
                savedJob.get("canonicalJobId"),
                rawJob.get("canonicalJobId"),
                rawJob.get("id"));
        Map<String, Object> generationMetadata =
                map(generated.get("generationMetadata"),
                        "generation metadata");
        validateClaimLedger(
                map(generated.get("claimLedger"), "claim ledger"));
        Map<String, Object> generationRequest =
                map(operation.data().get("generationRequest"),
                        "generation request");
        Map<String, Object> evidenceSnapshots =
                map(generationRequest.get("evidenceSnapshots"),
                        "evidence snapshots");
        String generatedAt =
                requiredText(operation.data(), "generationCompletedAt");

        Map<String, Object> cv = bounded(
                operation,
                () -> downstream.createDocument(
                        operation.ownerId(),
                        operation.id() + ":cv-document",
                        documentRequest(
                                operation.ownerId(),
                                jobId,
                                "CV",
                                requiredText(generated, "cvTitle"),
                                requiredText(generated, "cvContent"),
                                generationMetadata,
                                documentEvidenceProvenance(
                                        generated,
                                        map(evidenceSnapshots.get("cv"),
                                                "CV evidence snapshot"),
                                        generatedAt))));
        Map<String, Object> coverLetter = bounded(
                operation,
                () -> downstream.createDocument(
                        operation.ownerId(),
                        operation.id() + ":cover-letter-document",
                        documentRequest(
                                operation.ownerId(),
                                jobId,
                                "COVER_LETTER",
                                requiredText(generated, "coverLetterTitle"),
                                requiredText(generated, "coverLetterContent"),
                                generationMetadata,
                                documentEvidenceProvenance(
                                        generated,
                                        map(evidenceSnapshots.get(
                                                        "coverLetter"),
                                                "cover-letter evidence snapshot"),
                                        generatedAt))));
        Map<String, Object> data = data(operation);
        data.put("cvDocumentId",
                requiredUuid(cv, "id").toString());
        data.put("coverLetterDocumentId",
                requiredUuid(coverLetter, "id").toString());
        data.put("cvDocumentEvidence", cv);
        data.put("coverLetterDocumentEvidence", coverLetter);
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.DRAFTS_STORED,
                data,
                null,
                null);
    }

    private GenerationOperation advanceApproval(
            GenerationOperation operation,
            UUID leaseToken) {
        try {
            while (true) {
                switch (operation.state()) {
                    case AWAITING_APPROVAL -> {
                        GenerationOperation boundedOperation = operation;
                        bounded(operation, () -> downstream.approveDocument(
                                boundedOperation.ownerId(),
                                requiredUuid(
                                        boundedOperation.data(),
                                        "cvDocumentId")));
                        bounded(operation, () -> downstream.approveDocument(
                                boundedOperation.ownerId(),
                                requiredUuid(
                                        boundedOperation.data(),
                                        "coverLetterDocumentId")));
                        operation = checkpoint(
                                operation,
                                leaseToken,
                                GenerationOperationState.APPROVED,
                                operation.data(),
                                null,
                                null);
                    }
                    case APPROVED -> {
                        operation = checkpoint(
                                operation,
                                leaseToken,
                                GenerationOperationState.CV_EXPORT_IN_PROGRESS,
                                operation.data(),
                                null,
                                null);
                    }
                    case CV_EXPORT_IN_PROGRESS -> operation =
                            exportCv(operation, leaseToken);
                    case CV_EXPORTED -> {
                        operation = checkpoint(
                                operation,
                                leaseToken,
                                GenerationOperationState.COVER_LETTER_EXPORT_IN_PROGRESS,
                                operation.data(),
                                null,
                                null);
                    }
                    case COVER_LETTER_EXPORT_IN_PROGRESS -> operation =
                            exportCoverLetter(operation, leaseToken);
                    case EXPORTED -> operation = createApplication(
                            operation, leaseToken);
                    default -> {
                        return operation;
                    }
                }
            }
        } catch (GenerationDeadlineExceededException exception) {
            return deadlineFailure(operation, leaseToken, exception);
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                return checkpoint(
                        operation,
                        leaseToken,
                        GenerationOperationState.RECOVERY_REQUIRED,
                        operation.data(),
                        "APPROVAL_REQUEST_REJECTED",
                        message(exception));
            }
            return retryableFailure(
                    operation, leaseToken, exception);
        } catch (RestClientException exception) {
            return retryableFailure(
                    operation, leaseToken, exception);
        } catch (RuntimeException exception) {
            return checkpoint(
                    operation,
                    leaseToken,
                    GenerationOperationState.RECOVERY_REQUIRED,
                    operation.data(),
                    "APPROVAL_RECOVERY_REQUIRED",
                    exception.getMessage());
        }
    }

    private GenerationOperation exportCv(
            GenerationOperation operation,
            UUID leaseToken) {
        Map<String, Object> exported = bounded(
                operation,
                () -> downstream.exportDocument(
                        operation.ownerId(),
                        requiredUuid(operation.data(), "cvDocumentId"),
                        operation.id() + ":cv-export"));
        Map<String, Object> data = data(operation);
        data.put("cvDownloads", exported);
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.CV_EXPORTED,
                data,
                null,
                null);
    }

    private GenerationOperation exportCoverLetter(
            GenerationOperation operation,
            UUID leaseToken) {
        Map<String, Object> exported = bounded(
                operation,
                () -> downstream.exportDocument(
                        operation.ownerId(),
                        requiredUuid(
                                operation.data(),
                                "coverLetterDocumentId"),
                        operation.id() + ":cover-letter-export"));
        Map<String, Object> data = data(operation);
        data.put("coverLetterDownloads", exported);
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.EXPORTED,
                data,
                null,
                null);
    }

    private GenerationOperation createApplication(
            GenerationOperation operation,
            UUID leaseToken) {
        Map<String, Object> savedJob =
                map(operation.data().get("savedJob"), "saved job");
        Map<String, Object> job =
                map(savedJob.get("job"), "saved job");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", operation.ownerId());
        request.put("jobId", firstText(
                job.get("id"), savedJob.get("canonicalJobId")));
        putIfText(request, "canonicalJobId",
                firstText(savedJob.get("canonicalJobId"),
                        job.get("canonicalJobId")));
        putIfText(request, "provider", job.get("provider"));
        putIfText(request, "externalJobId",
                firstText(job.get("externalJobId"), job.get("id")));
        request.put("jobTitle", requiredJobText(job, "title", "jobTitle"));
        request.put("companyName",
                requiredJobText(job, "company", "companyName"));
        putIfText(request, "location", job.get("location"));
        request.put("cvDocumentId",
                requiredUuid(operation.data(), "cvDocumentId"));
        request.put("coverLetterDocumentId",
                requiredUuid(
                        operation.data(),
                        "coverLetterDocumentId"));
        request.put("provenance", "GENERATED");
        request.put("initialStatus", "DOCUMENTS_GENERATED");
        Map<String, Object> application;
        try {
            application = bounded(
                    operation,
                    () -> downstream.createApplication(
                            operation.ownerId(),
                            operation.id() + ":application",
                            request));
        } catch (HttpStatusCodeException conflict) {
            if (conflict.getStatusCode() != HttpStatus.CONFLICT) {
                throw conflict;
            }
            application = linkExistingApplication(
                    operation,
                    requiredText(request, "canonicalJobId"),
                    conflict);
        }
        Map<String, Object> data = data(operation);
        data.put("applicationId",
                requiredUuid(application, "id").toString());
        data.put("applicationEvidence", application);
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.COMPLETED,
                data,
                null,
                null);
    }

    private Map<String, Object> linkExistingApplication(
            GenerationOperation operation,
            String canonicalJobId,
            HttpStatusCodeException conflict) {
        Map<String, Object> application = bounded(
                operation,
                () -> downstream.applications(operation.ownerId()))
                .stream()
                .filter(candidate -> canonicalJobId.equals(firstText(
                        candidate.get("canonicalJobId"),
                        candidate.get("jobId"))))
                .findFirst()
                .orElseThrow(() -> conflict);
        UUID applicationId = requiredUuid(application, "id");
        bounded(
                operation,
                () -> downstream.updateApplicationDocument(
                        operation.ownerId(),
                        applicationId,
                        "CV",
                        requiredUuid(
                                operation.data(),
                                "cvDocumentId")));
        return bounded(
                operation,
                () -> downstream.updateApplicationDocument(
                        operation.ownerId(),
                        applicationId,
                        "COVER_LETTER",
                        requiredUuid(
                                operation.data(),
                                "coverLetterDocumentId")));
    }

    private boolean recoverableApplicationConflict(
            GenerationOperation operation) {
        String failure = Objects.toString(
                operation.failureMessage(), "")
                .toLowerCase(Locale.ROOT);
        return operation.state()
                        == GenerationOperationState.RECOVERY_REQUIRED
                && "APPROVAL_REQUEST_REJECTED".equals(
                        operation.failureCode())
                && failure.contains("\"status\":409")
                && failure.contains("canonical job")
                && operation.data().containsKey("cvDownloads")
                && operation.data().containsKey(
                        "coverLetterDownloads")
                && operation.data().containsKey("cvDocumentId")
                && operation.data().containsKey(
                        "coverLetterDocumentId")
                && !operation.data().containsKey("applicationId");
    }

    private Map<String, Object> jobSnapshot(
            GenerationOperation operation,
            Map<String, Object> savedJob,
            Map<String, Object> job,
            Instant capturedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provenance", provenance(
                "JOB_SERVICE",
                operation.savedJobId().toString(),
                firstText(
                        savedJob.get("contentVersion"),
                        savedJob.get("snapshotVersion")),
                capturedAt));
        result.put("title",
                bounded(requiredJobText(job, "title", "jobTitle"), 160));
        result.put("company",
                bounded(requiredJobText(job, "company", "companyName"), 160));
        result.put("description",
                bounded(requiredText(job, "description"), 12000));
        putBounded(result, "location", job.get("location"), 160);
        putBounded(result, "employmentType",
                firstText(
                        job.get("employmentType"),
                        job.get("employmentTypeCode"),
                        job.get("contractType")),
                80);
        String postedDate = isoDate(firstText(
                job.get("postedDate"), job.get("postedAt")));
        if (postedDate != null) {
            result.put("postedDate", postedDate);
        }
        return result;
    }

    private String isoDate(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 10) {
            try {
                return LocalDate.parse(
                        value.substring(0, 10),
                        DateTimeFormatter.ISO_LOCAL_DATE).toString();
            } catch (DateTimeParseException ignored) {
                // Try the provider's UK display-date format below.
            }
        }
        try {
            return LocalDate.parse(
                    value,
                    DateTimeFormatter.ofPattern("d/M/uuuu")).toString();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Map<String, Object> profileSnapshot(
            GenerationOperation operation,
            Map<String, Object> profile,
            Map<String, Object> account,
            Instant capturedAt) {
        String revisionId = requiredText(profile, "revisionId");
        String contentDigest = requiredText(profile, "contentDigest");
        try {
            UUID.fromString(revisionId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "User Profile Service returned an invalid revision ID.");
        }
        if (!CONTENT_SHA256.matcher(contentDigest).matches()) {
            throw new IllegalStateException(
                    "User Profile Service returned an invalid profile digest.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provenance", provenance(
                "USER_PROFILE_SERVICE",
                revisionId,
                "sha256:" + contentDigest,
                capturedAt));
        Map<String, Object> aspirations = optionalMap(
                profile.get("aspirations"));
        result.put("targetRoles", boundedStrings(
                list(aspirations.get("targetRoles")), 20, 120));
        Map<String, Object> workPreferences = optionalMap(
                profile.get("workPreferences"));
        String location = locationText(
                workPreferences.get("location"));
        if (location != null) {
            result.put("location", bounded(location, 160));
        }
        result.put("skills", List.of());
        result.put("qualifications", List.of());
        result.put("employmentHistory", List.of());

        String fullName = firstText(
                account.get("name"), account.get("fullName"));
        String email = text(account.get("email"));
        if (fullName != null || email != null) {
            Map<String, Object> contact = new LinkedHashMap<>();
            contact.put("provenance", provenance(
                    "AUTHENTICATION_SERVICE",
                    operation.ownerId(),
                    "sha256:" + sha256Json(account),
                    capturedAt));
            putBounded(contact, "fullName", fullName, 120);
            putBounded(contact, "email", email, 254);
            result.put("contact", contact);
        }
        return result;
    }

    private StartGenerationRequest selectionRequest(
            GenerationOperation operation) {
        Object value = operation.data().get("evidenceSelectionRequest");
        try {
            return objectMapper.convertValue(
                    value, StartGenerationRequest.class);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Generation operation has no valid evidence selection.",
                    exception);
        }
    }

    private DocumentEvidenceSelection selection(
            StartGenerationRequest request,
            DocumentPurpose purpose) {
        return request.documents().stream()
                .filter(document -> document.purpose() == purpose)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Generation evidence purpose is missing."));
    }

    private void validateSelectionRequest(StartGenerationRequest request) {
        if (request == null
                || request.documents() == null
                || request.documents().size() != 2) {
            throw new IllegalArgumentException(
                    "Exactly one CV and one cover-letter evidence selection are required.");
        }
        var purposes = new HashSet<DocumentPurpose>();
        for (DocumentEvidenceSelection document : request.documents()) {
            if (document == null
                    || document.purpose() == null
                    || document.entryIds() == null
                    || document.entryIds().isEmpty()
                    || document.entryIds().size() > 50
                    || document.sectionOrder() == null
                    || document.sectionOrder().isEmpty()
                    || document.sectionOrder().size() > 9
                    || new HashSet<>(document.entryIds()).size()
                            != document.entryIds().size()
                    || new HashSet<>(document.sectionOrder()).size()
                            != document.sectionOrder().size()
                    || !purposes.add(document.purpose())) {
                throw new IllegalArgumentException(
                        "Evidence selections must be bounded, unique and purpose-specific.");
            }
        }
        if (!purposes.equals(
                java.util.EnumSet.allOf(DocumentPurpose.class))) {
            throw new IllegalArgumentException(
                    "Both document evidence purposes are required.");
        }
    }

    private void validateEvidenceSnapshot(
            DocumentEvidenceSelection requested,
            Map<String, Object> snapshot) {
        requiredUuid(snapshot, "snapshotId");
        requiredUuid(snapshot, "profileRevisionId");
        String digest = requiredText(snapshot, "snapshotDigest");
        String profileDigest =
                requiredText(snapshot, "profileContentDigest");
        if (!CONTENT_SHA256.matcher(digest).matches()
                || !CONTENT_SHA256.matcher(profileDigest).matches()
                || !requested.purpose().name().equals(
                        requiredText(snapshot, "purpose"))) {
            throw new IllegalStateException(
                    "User Profile Service returned inconsistent evidence snapshot metadata.");
        }
        List<?> sections = list(snapshot.get("sectionOrder"));
        if (!sections.stream().map(this::text).toList().equals(
                requested.sectionOrder().stream()
                        .map(Enum::name)
                        .toList())) {
            throw new IllegalStateException(
                    "User Profile Service returned a different section order.");
        }
        List<?> selections = list(snapshot.get("selections"));
        List<String> returnedEntryIds = selections.stream()
                .map(this::optionalMap)
                .map(item -> requiredText(item, "entryId"))
                .toList();
        if (!returnedEntryIds.equals(requested.entryIds().stream()
                .map(UUID::toString)
                .toList())) {
            throw new IllegalStateException(
                    "User Profile Service returned a different evidence selection.");
        }
        var factIds = new HashSet<UUID>();
        for (Object value : selections) {
            Map<String, Object> selected = optionalMap(value);
            requiredUuid(selected, "revisionId");
            if (number(selected, "revisionNumber").longValue() < 1
                    || !requested.sectionOrder().stream()
                            .map(Enum::name)
                            .toList()
                            .contains(requiredText(selected, "category"))) {
                throw new IllegalStateException(
                        "Evidence selection has invalid revision or category metadata.");
            }
            String contentDigest =
                    requiredText(selected, "contentDigest");
            if (!CONTENT_SHA256.matcher(contentDigest).matches()) {
                throw new IllegalStateException(
                        "Evidence selection has an invalid revision digest.");
            }
            List<?> facts = list(selected.get("facts"));
            if (facts.isEmpty()) {
                throw new IllegalStateException(
                        "Evidence selection contains no approved facts.");
            }
            for (Object factValue : facts) {
                Map<String, Object> fact = optionalMap(factValue);
                UUID factId = requiredUuid(fact, "factId");
                if (!factIds.add(factId)
                        || text(fact.get("factType")) == null
                        || text(fact.get("factValue")) == null
                        || !(fact.get("numericClaim") instanceof Boolean)) {
                    throw new IllegalStateException(
                            "Evidence snapshot contains an invalid or duplicate fact.");
                }
            }
        }
        if (factIds.isEmpty()) {
            throw new IllegalStateException(
                "Evidence snapshot contains no approved facts.");
        }
    }

    private void validateProfileAndPurposeBindings(
            Map<String, Object> profile,
            Map<String, Object> cv,
            Map<String, Object> coverLetter) {
        UUID profileRevisionId = requiredUuid(profile, "revisionId");
        String profileDigest = requiredText(profile, "contentDigest");
        UUID cvProfileRevisionId =
                requiredUuid(cv, "profileRevisionId");
        UUID coverProfileRevisionId =
                requiredUuid(coverLetter, "profileRevisionId");
        String cvProfileDigest =
                requiredText(cv, "profileContentDigest");
        String coverProfileDigest =
                requiredText(coverLetter, "profileContentDigest");
        UUID cvSnapshotId = requiredUuid(cv, "snapshotId");
        UUID coverSnapshotId =
                requiredUuid(coverLetter, "snapshotId");
        if (!profileRevisionId.equals(cvProfileRevisionId)
                || !profileRevisionId.equals(coverProfileRevisionId)
                || !profileDigest.equals(cvProfileDigest)
                || !profileDigest.equals(coverProfileDigest)
                || cvSnapshotId.equals(coverSnapshotId)) {
            throw new IllegalStateException(
                    "Evidence snapshots are not bound to the exact current profile revision and distinct document purposes.");
        }
    }

    private List<Map<String, Object>> qualifications(List<?> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> source = optionalMap(value);
            String name = text(source.get("qualificationName"));
            if (name == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("qualificationName", bounded(name, 160));
            putBounded(item, "issuingBody", source.get("issuingBody"), 160);
            putBounded(item, "grade", source.get("grade"), 80);
            putBounded(item, "dateAchieved",
                    source.get("dateAchieved"), 10);
            putBounded(item, "expectedCompletion",
                    source.get("expectedCompletion"), 10);
            String status = text(source.get("status"));
            if (status != null) {
                item.put("status",
                        status.toUpperCase(Locale.ROOT).contains("PROGRESS")
                                ? "IN_PROGRESS"
                                : "COMPLETED");
            }
            result.add(item);
            if (result.size() == 30) {
                break;
            }
        }
        return result;
    }

    private List<Map<String, Object>> employment(List<?> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            Map<String, Object> source = optionalMap(value);
            String jobTitle = text(source.get("jobTitle"));
            String employer = text(source.get("employer"));
            String startDate = text(source.get("startDate"));
            if (jobTitle == null || employer == null || startDate == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jobTitle", bounded(jobTitle, 160));
            item.put("employer", bounded(employer, 160));
            item.put("startDate", bounded(startDate, 10));
            putBounded(item, "endDate", source.get("endDate"), 10);
            putBounded(item, "responsibilities",
                    source.get("keyResponsibilities"), 4000);
            String status = text(source.get("status"));
            if (status != null) {
                item.put("status",
                        status.toUpperCase(Locale.ROOT).contains("CURRENT")
                                ? "CURRENT"
                                : "PREVIOUS_ROLE");
            }
            result.add(item);
            if (result.size() == 30) {
                break;
            }
        }
        return result;
    }

    private Map<String, Object> provenance(
            String owner,
            String resourceId,
            String version,
            Instant capturedAt) {
        return Map.of(
                "owner", owner,
                "resourceId", bounded(resourceId, 128),
                "version", bounded(version, 128),
                "capturedAt", capturedAt.toString());
    }

    private Map<String, Object> documentRequest(
            String ownerId,
            String jobId,
            String documentType,
            String title,
            String content,
            Map<String, Object> generationMetadata,
            Map<String, Object> evidenceProvenance) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userId", ownerId);
        request.put("jobId", jobId);
        request.put("documentType", documentType);
        request.put("title", title);
        request.put("content", content);
        request.put("sourceType", "GENERATED");
        request.put("generationMetadata", generationMetadata);
        request.put("evidenceProvenance", evidenceProvenance);
        request.put("createdBy", "document-generation-gateway");
        return request;
    }

    private Map<String, Object> documentEvidenceProvenance(
            Map<String, Object> generated,
            Map<String, Object> snapshot,
            String generatedAt) {
        List<Map<String, Object>> evidenceRevisions = list(
                        snapshot.get("selections"))
                .stream()
                .map(this::optionalMap)
                .map(selected -> Map.<String, Object>of(
                        "entryId", requiredUuid(
                                selected, "entryId"),
                        "revisionId", requiredUuid(
                                selected, "revisionId"),
                        "revisionNumber", number(
                                selected, "revisionNumber").intValue(),
                        "category", requiredText(
                                selected, "category"),
                        "contentDigest", requiredText(
                                selected, "contentDigest")))
                .toList();
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put(
                "profileRevisionId",
                requiredUuid(snapshot, "profileRevisionId"));
        provenance.put(
                "profileContentDigest",
                requiredText(snapshot, "profileContentDigest"));
        provenance.put(
                "evidenceSnapshotId",
                requiredUuid(snapshot, "snapshotId"));
        provenance.put(
                "evidenceSnapshotDigest",
                requiredText(snapshot, "snapshotDigest"));
        provenance.put("evidenceRevisions", evidenceRevisions);
        provenance.put(
                "sectionOrder",
                list(snapshot.get("sectionOrder")).stream()
                        .map(this::text)
                        .toList());
        provenance.put(
                "claimLedger",
                map(generated.get("claimLedger"), "claim ledger"));
        provenance.put("generatedAt", generatedAt);
        return provenance;
    }

    private void validateClaimLedger(Map<String, Object> ledger) {
        requiredUuid(ledger, "ledgerId");
        if (!CONTENT_SHA256.matcher(
                requiredText(ledger, "ledgerSha256")).matches()
                || text(ledger.get("policyVersion")) == null
                || text(ledger.get("parserVersion")) == null) {
            throw new IllegalStateException(
                    "CV Service returned invalid claim-ledger provenance.");
        }
        List<?> claims = list(ledger.get("claims"));
        if (claims.isEmpty() || claims.size() > MAX_LEDGER_CLAIMS) {
            throw new IllegalStateException(
                    "CV Service returned an invalid claim ledger.");
        }
        for (Object value : claims) {
            Map<String, Object> claim = optionalMap(value);
            String claimId = requiredText(claim, "claimId");
            String disposition = requiredText(claim, "disposition");
            List<?> evidenceIds = list(claim.get("evidenceIds"));
            List<?> contentPaths = list(claim.get("contentPaths"));
            Object rawReviewText = claim.get("reviewText");
            String reviewText = rawReviewText instanceof String review
                    ? review
                    : null;
            if (!claimId.matches("CLAIM-[0-9]{3,4}")
                    || !List.of(
                                    "SUPPORTED",
                                    "REWORDED",
                                    "CONFIRMATION_REQUIRED",
                                    "REJECTED")
                            .contains(disposition)
                    || evidenceIds.size() > MAX_CLAIM_REFERENCES
                    || contentPaths.size() > MAX_CLAIM_REFERENCES
                    || reviewText == null
                    || reviewText.length() > MAX_CLAIM_REVIEW_TEXT) {
                throw new IllegalStateException(
                        "CV Service returned an invalid claim ledger.");
            }
        }
    }

    private void verifyApprovalRequest(
            GenerationOperation operation,
            ApproveGenerationRequest request) {
        Objects.requireNonNull(request, "Approval request is required.");
        UUID storedCv = requiredUuid(
                operation.data(), "cvDocumentId");
        UUID storedLetter = requiredUuid(
                operation.data(), "coverLetterDocumentId");
        if (!storedCv.equals(request.cvDocumentId())
                || !storedLetter.equals(
                        request.coverLetterDocumentId())) {
            throw new GenerationConflictException(
                    "Approval must reference the exact drafts created by this operation.");
        }
        if (operation.state()
                != GenerationOperationState.AWAITING_APPROVAL
                && operation.state()
                != GenerationOperationState.APPROVED
                && operation.state()
                != GenerationOperationState.CV_EXPORT_IN_PROGRESS
                && operation.state()
                != GenerationOperationState.CV_EXPORTED
                && operation.state()
                != GenerationOperationState.COVER_LETTER_EXPORT_IN_PROGRESS
                && operation.state()
                != GenerationOperationState.EXPORTED
                && operation.state()
                != GenerationOperationState.COMPLETED) {
            throw new GenerationConflictException(
                    "Generation operation is not awaiting approval.");
        }
    }

    private GenerationOperation releaseAndFail(
            GenerationOperation operation,
            UUID leaseToken,
            String code,
            String message) {
        UUID reservationId = uuid(
                operation.data(), "reservationId");
        if (reservationId != null) {
            try {
                downstream.release(
                        operation.ownerId(),
                        reservationId,
                        code);
            } catch (RestClientException releaseFailure) {
                return checkpoint(
                        operation,
                        leaseToken,
                        GenerationOperationState.RECOVERY_REQUIRED,
                        operation.data(),
                        "CREDIT_RELEASE_RECOVERY_REQUIRED",
                        "The generation failed and its credit hold could not be confirmed released.");
            }
        }
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.FAILED,
                operation.data(),
                code,
                message);
    }

    private GenerationOperation deadlineFailure(
            GenerationOperation operation,
            UUID leaseToken,
            GenerationDeadlineExceededException exception) {
        if (operation.state()
                == GenerationOperationState.GENERATION_IN_PROGRESS) {
            return generationOutcomeUnknown(
                    operation, leaseToken, exception);
        }
        if (operation.state() == GenerationOperationState.ESTIMATED
                && exception.downstreamCallStarted()) {
            return checkpoint(
                    operation,
                    leaseToken,
                    GenerationOperationState.RECOVERY_REQUIRED,
                    operation.data(),
                    "CREDIT_RESERVATION_RECOVERY_REQUIRED",
                    "The deadline expired while reserving AI Credit; recover the stable operation reservation before continuing.");
        }
        if (beforeGeneration(operation.state())) {
            return releaseAndFail(
                    operation,
                    leaseToken,
                    "OPERATION_DEADLINE_EXCEEDED",
                    exception.getMessage());
        }
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.RECOVERY_REQUIRED,
                operation.data(),
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                "The operation deadline expired during a replay-safe side effect; recover using the persisted operation and stable downstream key.");
    }

    private GenerationOperation retryableFailure(
            GenerationOperation operation,
            UUID leaseToken,
            RuntimeException exception) {
        return checkpoint(
                operation,
                leaseToken,
                operation.state(),
                operation.data(),
                "DOWNSTREAM_RETRYABLE",
                "A replay-safe downstream step did not complete: "
                        + exception.getClass().getSimpleName());
    }

    private GenerationOperation generationOutcomeUnknown(
            GenerationOperation operation,
            UUID leaseToken,
            RuntimeException exception) {
        return checkpoint(
                operation,
                leaseToken,
                GenerationOperationState.GENERATION_OUTCOME_UNKNOWN,
                operation.data(),
                "GENERATION_OUTCOME_UNKNOWN",
                "The provider outcome is ambiguous; automatic retry is disabled to prevent duplicate cost ("
                        + exception.getClass().getSimpleName()
                        + ").");
    }

    private GenerationOperation checkpoint(
            GenerationOperation operation,
            UUID leaseToken,
            GenerationOperationState state,
            Map<String, Object> data,
            String failureCode,
            String failureMessage) {
        return repository.checkpoint(
                operation,
                leaseToken,
                state,
                data,
                failureCode,
                failureMessage);
    }

    private <T> T bounded(
            GenerationOperation operation,
            Supplier<T> downstreamCall) {
        return deadlineGuard.call(
                operation.deadlineAt(), downstreamCall);
    }

    private void bounded(
            GenerationOperation operation,
            Runnable downstreamCall) {
        deadlineGuard.run(
                operation.deadlineAt(), downstreamCall);
    }

    private GenerationOperation required(UUID id, String ownerId) {
        return repository.findByOwnerAndId(id, ownerId)
                .orElseThrow(GenerationNotFoundException::new);
    }

    private GenerationOperationResponse response(
            GenerationOperation operation) {
        boolean manual = operation.state()
                == GenerationOperationState.GENERATION_OUTCOME_UNKNOWN
                || operation.state()
                == GenerationOperationState.RECOVERY_REQUIRED;
        Map<String, Object> downloads = new LinkedHashMap<>();
        if (operation.data().containsKey("cvDownloads")) {
            downloads.put("cv", operation.data().get("cvDownloads"));
        }
        if (operation.data().containsKey("coverLetterDownloads")) {
            downloads.put(
                    "coverLetter",
                    operation.data().get("coverLetterDownloads"));
        }
        return new GenerationOperationResponse(
                operation.id(),
                operation.savedJobId(),
                operation.state(),
                !manual,
                manual,
                uuid(operation.data(), "cvDocumentId"),
                uuid(operation.data(), "coverLetterDocumentId"),
                uuid(operation.data(), "applicationId"),
                downloads,
                operation.failureCode(),
                operation.failureMessage(),
                operation.deadlineAt(),
                operation.createdAt(),
                operation.updatedAt());
    }

    private Map<String, Object> data(
            GenerationOperation operation) {
        return new LinkedHashMap<>(operation.data());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value, String label) {
        if (value instanceof Map<?, ?> source) {
            return new LinkedHashMap<>(
                    (Map<String, Object>) source);
        }
        throw new IllegalStateException(
                "Missing or invalid " + label + ".");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> optionalMap(Object value) {
        if (value instanceof Map<?, ?> source) {
            return new LinkedHashMap<>(
                    (Map<String, Object>) source);
        }
        return Map.of();
    }

    private List<?> list(Object value) {
        return value instanceof List<?> values
                ? values
                : List.of();
    }

    private List<String> boundedStrings(
            List<?> values,
            int maximumItems,
            int maximumLength) {
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String text = text(value);
            if (text != null) {
                result.add(bounded(text, maximumLength));
            }
            if (result.size() == maximumItems) {
                break;
            }
        }
        return result;
    }

    private String locationText(Object value) {
        if (value instanceof Map<?, ?> location) {
            return firstText(
                    location.get("displayName"),
                    location.get("formatted"),
                    location.get("postcode"),
                    location.get("town"));
        }
        return text(value);
    }

    private String requiredJobText(
            Map<String, Object> job,
            String first,
            String second) {
        String value = firstText(job.get(first), job.get(second));
        if (value == null) {
            throw new IllegalStateException(
                    "Saved Job is missing " + first + ".");
        }
        return value;
    }

    private String requiredText(
            Map<String, Object> values,
            String key) {
        String value = text(values.get(key));
        if (value == null) {
            throw new IllegalStateException(
                    "A downstream response is missing " + key + ".");
        }
        return value;
    }

    private Number number(
            Map<String, Object> values,
            String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalStateException(
                "A downstream response is missing " + key + ".");
    }

    private UUID requiredUuid(
            Map<String, Object> values,
            String key) {
        UUID value = uuid(values, key);
        if (value == null) {
            throw new IllegalStateException(
                    "A downstream response is missing " + key + ".");
        }
        return value;
    }

    private UUID uuid(
            Map<String, Object> values,
            String key) {
        Object value = values.get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "A downstream response contains an invalid "
                            + key
                            + ".");
        }
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void putIfText(
            Map<String, Object> target,
            String key,
            Object value) {
        String text = text(value);
        if (text != null) {
            target.put(key, text);
        }
    }

    private void putBounded(
            Map<String, Object> target,
            String key,
            Object value,
            int maximumLength) {
        String text = text(value);
        if (text != null) {
            target.put(key, bounded(text, maximumLength));
        }
    }

    private String bounded(String value, int maximumLength) {
        return value.length() <= maximumLength
                ? value
                : value.substring(0, maximumLength);
    }

    private String sha256Json(Object value) {
        try {
            return sha256(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Snapshot could not be canonicalized.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable.", exception);
        }
    }

    private String message(HttpStatusCodeException exception) {
        String responseBody = exception.getResponseBodyAsString();
        return responseBody == null || responseBody.isBlank()
                ? exception.getMessage()
                : responseBody;
    }

    private boolean beforeGeneration(
            GenerationOperationState state) {
        return state == GenerationOperationState.CREATED
                || state == GenerationOperationState.SNAPSHOTS_RESOLVED
                || state == GenerationOperationState.ESTIMATED
                || state == GenerationOperationState.CREDIT_RESERVED;
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null
                || ownerId.isBlank()
                || ownerId.length() > 128
                || ownerId.indexOf(',') >= 0
                || ownerId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "A valid authenticated owner is required.");
        }
    }

    private void requireAuthorization(String authorization) {
        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.length() <= "Bearer ".length()) {
            throw new IllegalArgumentException(
                    "The validated bearer token is required.");
        }
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must use 1-128 safe characters.");
        }
    }

    private static Duration requirePositive(
            Duration value,
            String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    "Document generation "
                            + label
                            + " must be positive.");
        }
        return value;
    }

    private static final class GenerationSourceException
            extends RuntimeException {
        private final String code;
        private final boolean retryable;

        private GenerationSourceException(
                String code,
                String message,
                boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        private String code() {
            return code;
        }

        private boolean retryable() {
            return retryable;
        }
    }
}
