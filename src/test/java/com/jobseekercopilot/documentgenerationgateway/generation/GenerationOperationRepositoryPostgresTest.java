package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.ApproveGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentEvidenceSelection;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentPurpose;
import com.jobseekercopilot.documentgenerationgateway.dto.EvidenceSection;
import com.jobseekercopilot.documentgenerationgateway.dto.StartGenerationRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.FlywayException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GenerationOperationRepositoryPostgresTest {
    private static final String OWNER = "postgres-owner";
    private static final String AUTHORIZATION = "Bearer postgres-test-token";
    private static final UUID SAVED_JOB_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000091");
    private static final UUID RESERVATION_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000091");
    private static final UUID CV_DOCUMENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000091");
    private static final UUID COVER_LETTER_DOCUMENT_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000092");
    private static final UUID APPLICATION_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000091");
    private static final UUID CV_EVIDENCE_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000091");
    private static final UUID COVER_LETTER_EVIDENCE_ID =
            UUID.fromString("50000000-0000-4000-8000-000000000092");
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add(
                "spring.flyway.locations",
                () -> "classpath:db/migration,"
                        + "classpath:db/postgresql-migration");
    }

    @Autowired private GenerationOperationRepository repository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OperationDeadlineGuard deadlineGuard;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private GenerationDownstreamClient downstream;

    @BeforeEach
    void clearOperations() {
        jdbc.update("DELETE FROM generation_operations");
        reset(downstream);
    }

    @Test
    void migrationSerializesOneActiveFingerprintAcrossDifferentKeys()
            throws Exception {
        UUID savedJobId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.createOrReplay(
                        "owner-1",
                        "concurrent-key-a",
                        savedJobId,
                        "a".repeat(64),
                        Duration.ofMinutes(10));
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return repository.createOrReplay(
                        "owner-1",
                        "concurrent-key-b",
                        savedJobId,
                        "a".repeat(64),
                        Duration.ofMinutes(10));
            });
            start.countDown();
            GenerationOperation firstOperation =
                    first.get(10, TimeUnit.SECONDS);
            GenerationOperation secondOperation =
                    second.get(10, TimeUnit.SECONDS);

            assertEquals(
                    firstOperation.id(), secondOperation.id());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM generation_operations",
                            Integer.class));

            UUID firstLease = UUID.randomUUID();
            UUID secondLease = UUID.randomUUID();
            List<Boolean> leaseResults = List.of(
                    repository.tryAcquire(
                            firstOperation.id(),
                            "owner-1",
                            firstLease,
                            Duration.ofSeconds(30)),
                    repository.tryAcquire(
                            firstOperation.id(),
                            "owner-1",
                            secondLease,
                            Duration.ofSeconds(30)));
            assertEquals(1, leaseResults.stream()
                    .filter(Boolean::booleanValue)
                    .count());
            GenerationOperation inProgress = repository.checkpoint(
                    firstOperation,
                    firstLease,
                    GenerationOperationState.GENERATION_IN_PROGRESS,
                    firstOperation.data(),
                    null,
                    null);
            GenerationOperation activeReplay =
                    repository.createOrReplay(
                            "owner-1",
                            "concurrent-key-c",
                            savedJobId,
                            "a".repeat(64),
                            Duration.ofMinutes(10));
            assertEquals(inProgress.id(), activeReplay.id());

            GenerationOperation completed = repository.checkpoint(
                    inProgress,
                    firstLease,
                    GenerationOperationState.COMPLETED,
                    inProgress.data(),
                    null,
                    null);
            repository.release(
                    completed.id(), "owner-1", firstLease);
            GenerationOperation explicitRegeneration =
                    repository.createOrReplay(
                            "owner-1",
                            "concurrent-key-d",
                            savedJobId,
                            "a".repeat(64),
                            Duration.ofMinutes(10));
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    completed.id(), explicitRegeneration.id());
            GenerationOperation otherOwner =
                    repository.createOrReplay(
                            "owner-2",
                            "concurrent-key-owner-2",
                            savedJobId,
                            "a".repeat(64),
                            Duration.ofMinutes(10));
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    explicitRegeneration.id(), otherOwner.id());
            assertEquals(
                    3,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM generation_operations",
                            Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void independentServicesChargeAndCreateOnlyOnceForDifferentKeys()
            throws Exception {
        CountDownLatch generationEntered = new CountDownLatch(1);
        CountDownLatch releaseGeneration = new CountDownLatch(1);
        successfulDownstream(generationEntered, releaseGeneration);
        var firstService = generationService();
        var secondService = generationService();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> firstService.start(
                    OWNER,
                    AUTHORIZATION,
                    SAVED_JOB_ID,
                    "postgres-service-key-a",
                    selectionRequest()));
            assertTrue(generationEntered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> secondService.start(
                    OWNER,
                    AUTHORIZATION,
                    SAVED_JOB_ID,
                    "postgres-service-key-b",
                    selectionRequest()));
            var secondAccepted = second.get(5, TimeUnit.SECONDS);
            releaseGeneration.countDown();
            var firstAccepted = first.get(5, TimeUnit.SECONDS);

            assertEquals(
                    firstAccepted.operationId(),
                    secondAccepted.operationId());
            assertEquals(
                    GenerationOperationState.AWAITING_APPROVAL,
                    firstService.get(
                            OWNER,
                            firstAccepted.operationId()).state());

            secondService.approve(
                    OWNER,
                    firstAccepted.operationId(),
                    new ApproveGenerationRequest(
                            CV_DOCUMENT_ID,
                            COVER_LETTER_DOCUMENT_ID));
            assertEquals(
                    GenerationOperationState.COMPLETED,
                    secondService.get(
                            OWNER,
                            firstAccepted.operationId()).state());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM generation_operations",
                            Integer.class));
            verify(downstream, times(1)).reserve(
                    OWNER, firstAccepted.operationId(), 1000);
            verify(downstream, times(1)).generate(
                    org.mockito.ArgumentMatchers.eq(OWNER),
                    org.mockito.ArgumentMatchers.eq(
                            firstAccepted.operationId()),
                    anyMap());
            verify(downstream, times(1)).createApplication(
                    org.mockito.ArgumentMatchers.eq(OWNER),
                    org.mockito.ArgumentMatchers.eq(
                            firstAccepted.operationId()
                                    + ":application"),
                    anyMap());
        } finally {
            releaseGeneration.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void retryableReplayPreparationRespectsAnActiveLease() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-retry-preparation",
                SAVED_JOB_ID,
                "b".repeat(64),
                Duration.ofMinutes(10));
        UUID initialLease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, initialLease,
                Duration.ofSeconds(30)));
        GenerationOperation retryable = repository.checkpoint(
                created,
                initialLease,
                GenerationOperationState.CREDIT_COMMITTED,
                created.data(),
                "DOWNSTREAM_RETRYABLE",
                "Document storage is temporarily unavailable.");
        repository.release(retryable.id(), OWNER, initialLease);
        jdbc.update("""
                UPDATE generation_operations
                   SET deadline_at = ?
                 WHERE id = ? AND owner_id = ?
                """,
                OffsetDateTime.ofInstant(
                        Instant.now().minusSeconds(1),
                        ZoneOffset.UTC),
                retryable.id(),
                OWNER);
        retryable = repository.findByOwnerAndId(
                        retryable.id(), OWNER)
                .orElseThrow();

        UUID competingLease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                retryable.id(), OWNER, competingLease,
                Duration.ofSeconds(30)));
        GenerationOperation leased = repository.findByOwnerAndId(
                        retryable.id(), OWNER)
                .orElseThrow();
        GenerationOperation blocked = repository.prepareRetryableReplay(
                leased, Duration.ofMinutes(10));

        assertEquals(
                "DOWNSTREAM_RETRYABLE", blocked.failureCode());
        assertTrue(blocked.deadlineAt().isBefore(Instant.now()));
        assertEquals(competingLease, blocked.leaseToken());
        assertEquals(leased.version(), blocked.version());

        repository.release(blocked.id(), OWNER, competingLease);
        GenerationOperation available = repository.findByOwnerAndId(
                        blocked.id(), OWNER)
                .orElseThrow();
        GenerationOperation prepared = repository.prepareRetryableReplay(
                available, Duration.ofMinutes(10));

        assertNull(prepared.failureCode());
        assertNull(prepared.failureMessage());
        assertNull(prepared.leaseToken());
        assertNull(prepared.leaseUntil());
        assertTrue(prepared.deadlineAt().isAfter(
                Instant.now().plus(Duration.ofMinutes(9))));
        assertEquals(available.version() + 1, prepared.version());
    }

    @Test
    void expiredSnapshotCheckpointIsPreparedForPreProviderReplay() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-pre-provider-deadline",
                SAVED_JOB_ID,
                "d".repeat(64),
                Map.of(
                        "evidenceSelectionRequest", Map.of(),
                        "generationRequest", Map.of("inputSchemaVersion", "2.0")),
                Duration.ofMinutes(10));
        UUID lease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, lease, Duration.ofSeconds(30)));
        GenerationOperation failed = repository.checkpoint(
                created,
                lease,
                GenerationOperationState.FAILED,
                created.data(),
                "OPERATION_DEADLINE_EXCEEDED",
                "The operation deadline was exceeded.");
        repository.release(failed.id(), OWNER, lease);

        GenerationOperation prepared = repository.prepareRetryableReplay(
                repository.findByOwnerAndId(failed.id(), OWNER).orElseThrow(),
                Duration.ofMinutes(10));

        assertEquals(
                GenerationOperationState.SNAPSHOTS_RESOLVED,
                prepared.state());
        assertNull(prepared.failureCode());
        assertNull(prepared.failureMessage());
        assertTrue(prepared.deadlineAt().isAfter(
                Instant.now().plus(Duration.ofMinutes(9))));
    }

    @Test
    void deadlineFailureWithProviderOutputIsNeverPreparedForReplay() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-ambiguous-deadline",
                SAVED_JOB_ID,
                "e".repeat(64),
                Map.of(
                        "generationRequest", Map.of("inputSchemaVersion", "2.0"),
                        "generation", Map.of("documents", List.of()),
                        "actualTokens", 100),
                Duration.ofMinutes(10));
        UUID lease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, lease, Duration.ofSeconds(30)));
        GenerationOperation failed = repository.checkpoint(
                created,
                lease,
                GenerationOperationState.FAILED,
                created.data(),
                "OPERATION_DEADLINE_EXCEEDED",
                "Retained ambiguous legacy failure.");
        repository.release(failed.id(), OWNER, lease);

        GenerationOperation unchanged = repository.prepareRetryableReplay(
                repository.findByOwnerAndId(failed.id(), OWNER).orElseThrow(),
                Duration.ofMinutes(10));

        assertEquals(GenerationOperationState.FAILED, unchanged.state());
        assertEquals("OPERATION_DEADLINE_EXCEEDED", unchanged.failureCode());
        assertEquals(failed.version(), unchanged.version());
    }

    @Test
    void releasedReservationDeadlineIsNotReplayedUnderItsStableKey() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-released-reservation-deadline",
                SAVED_JOB_ID,
                "f".repeat(64),
                Map.of(
                        "generationRequest", Map.of("inputSchemaVersion", "2.0"),
                        "estimatedTokens", 1000,
                        "reservationId", RESERVATION_ID.toString()),
                Duration.ofMinutes(10));
        UUID lease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, lease, Duration.ofSeconds(30)));
        GenerationOperation failed = repository.checkpoint(
                created,
                lease,
                GenerationOperationState.FAILED,
                created.data(),
                "OPERATION_DEADLINE_EXCEEDED",
                "The released reservation cannot reuse its stable key.");
        repository.release(failed.id(), OWNER, lease);

        GenerationOperation unchanged = repository.prepareRetryableReplay(
                repository.findByOwnerAndId(failed.id(), OWNER).orElseThrow(),
                Duration.ofMinutes(10));

        assertEquals(GenerationOperationState.FAILED, unchanged.state());
        assertEquals("OPERATION_DEADLINE_EXCEEDED", unchanged.failureCode());
        assertEquals(failed.version(), unchanged.version());
    }

    @Test
    void deadlineRecoveryRestoresOnlyARecordedPostModelCheckpoint() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-deadline-recovery",
                SAVED_JOB_ID,
                "c".repeat(64),
                Duration.ofMinutes(10));
        UUID lease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, lease,
                Duration.ofSeconds(30)));
        GenerationOperation recovery = repository.checkpoint(
                created,
                lease,
                GenerationOperationState.RECOVERY_REQUIRED,
                Map.of(
                        GenerationOperationRepository
                                .DEADLINE_RECOVERY_STATE_KEY,
                        GenerationOperationState.CREDIT_COMMITTED.name()),
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                "Replay-safe work exceeded its deadline.");
        repository.release(recovery.id(), OWNER, lease);

        GenerationOperation prepared = repository.prepareRetryableReplay(
                repository.findByOwnerAndId(
                                recovery.id(), OWNER)
                        .orElseThrow(),
                Duration.ofMinutes(10));

        assertEquals(
                GenerationOperationState.CREDIT_COMMITTED,
                prepared.state());
        assertNull(prepared.failureCode());
        assertNull(prepared.failureMessage());
        assertTrue(prepared.deadlineAt().isAfter(
                Instant.now().plus(Duration.ofMinutes(9))));
    }

    @Test
    void ambiguousDeadlineRecoveryRemainsTerminal() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-ambiguous-deadline-recovery",
                SAVED_JOB_ID,
                "d".repeat(64),
                Duration.ofMinutes(10));
        UUID lease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, lease,
                Duration.ofSeconds(30)));
        GenerationOperation recovery = repository.checkpoint(
                created,
                lease,
                GenerationOperationState.RECOVERY_REQUIRED,
                Map.of(),
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                "Ambiguous retained recovery.");
        repository.release(recovery.id(), OWNER, lease);
        GenerationOperation available = repository.findByOwnerAndId(
                        recovery.id(), OWNER)
                .orElseThrow();

        GenerationOperation unchanged =
                repository.prepareRetryableReplay(
                        available, Duration.ofMinutes(10));

        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                unchanged.state());
        assertEquals(
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                unchanged.failureCode());
        assertEquals(available.version(), unchanged.version());
        assertEquals(available.deadlineAt(), unchanged.deadlineAt());
    }

    @Test
    void unsafeRecordedDeadlineCheckpointDoesNotUseLegacyFallback() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-unsafe-recorded-deadline-recovery",
                SAVED_JOB_ID,
                "e".repeat(64),
                Duration.ofMinutes(10));
        UUID lease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, lease,
                Duration.ofSeconds(30)));
        GenerationOperation recovery = repository.checkpoint(
                created,
                lease,
                GenerationOperationState.RECOVERY_REQUIRED,
                Map.of(
                        GenerationOperationRepository
                                .DEADLINE_RECOVERY_STATE_KEY,
                        GenerationOperationState.CREATED.name(),
                        "generation", Map.of("retained", true),
                        "actualTokens", 600,
                        "reservationId", UUID.randomUUID().toString()),
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                "Unsafe recorded checkpoint.");
        repository.release(recovery.id(), OWNER, lease);

        GenerationOperation unchanged =
                repository.prepareRetryableReplay(
                        repository.findByOwnerAndId(
                                        recovery.id(), OWNER)
                                .orElseThrow(),
                        Duration.ofMinutes(10));

        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                unchanged.state());
        assertEquals(
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                unchanged.failureCode());
    }

    @Test
    void zeroTokenLegacyDeadlineCheckpointRemainsTerminal() {
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-zero-token-deadline-recovery",
                SAVED_JOB_ID,
                "f".repeat(64),
                Duration.ofMinutes(10));
        UUID lease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, lease,
                Duration.ofSeconds(30)));
        GenerationOperation recovery = repository.checkpoint(
                created,
                lease,
                GenerationOperationState.RECOVERY_REQUIRED,
                Map.of(
                        "generation", Map.of("retained", true),
                        "actualTokens", 0,
                        "reservationId", UUID.randomUUID().toString()),
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                "Invalid legacy checkpoint.");
        repository.release(recovery.id(), OWNER, lease);

        GenerationOperation unchanged =
                repository.prepareRetryableReplay(
                        repository.findByOwnerAndId(
                                        recovery.id(), OWNER)
                                .orElseThrow(),
                        Duration.ofMinutes(10));

        assertEquals(
                GenerationOperationState.RECOVERY_REQUIRED,
                unchanged.state());
        assertEquals(
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                unchanged.failureCode());
    }

    @Test
    void olderDeadlineRecoveryIsNotSelectedAfterANewerOperation() {
        String fingerprint = "1".repeat(64);
        GenerationOperation created = repository.createOrReplay(
                OWNER,
                "postgres-old-deadline-recovery",
                SAVED_JOB_ID,
                fingerprint,
                Duration.ofMinutes(10));
        UUID recoveryLease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                created.id(), OWNER, recoveryLease,
                Duration.ofSeconds(30)));
        GenerationOperation recovery = repository.checkpoint(
                created,
                recoveryLease,
                GenerationOperationState.RECOVERY_REQUIRED,
                Map.of(
                        GenerationOperationRepository
                                .DEADLINE_RECOVERY_STATE_KEY,
                        GenerationOperationState.CREDIT_COMMITTED.name()),
                "OPERATION_DEADLINE_RECOVERY_REQUIRED",
                "Older retained recovery.");
        repository.release(recovery.id(), OWNER, recoveryLease);
        assertEquals(
                1,
                jdbc.update(
                        """
                        UPDATE generation_operations
                           SET created_at = ?
                         WHERE id = ? AND owner_id = ?
                        """,
                        OffsetDateTime.ofInstant(
                                Instant.now().minus(Duration.ofMinutes(1)),
                                ZoneOffset.UTC),
                        recovery.id(),
                        OWNER));

        GenerationOperation newer = repository.createOrReplay(
                OWNER,
                "postgres-newer-terminal-operation",
                SAVED_JOB_ID,
                fingerprint,
                Duration.ofMinutes(10));
        UUID newerLease = UUID.randomUUID();
        assertTrue(repository.tryAcquire(
                newer.id(), OWNER, newerLease,
                Duration.ofSeconds(30)));
        GenerationOperation failed = repository.checkpoint(
                newer,
                newerLease,
                GenerationOperationState.FAILED,
                Map.of(),
                "GENERATION_REJECTED",
                "Newer terminal operation.");
        repository.release(failed.id(), OWNER, newerLease);

        assertTrue(repository.findLatestDeadlineRecovery(
                        OWNER, SAVED_JOB_ID, fingerprint)
                .isEmpty());
    }

    @Test
    void v3AppliesToRetainedV2DataAndRejectsANewActiveDuplicate() {
        String schema = "retained_"
                + UUID.randomUUID().toString().replace("-", "");
        var retainedDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        migrate(retainedDataSource, schema, MigrationVersion.fromVersion("2"));
        var retainedJdbc = new JdbcTemplate(retainedDataSource);
        UUID savedJobId = UUID.randomUUID();
        insertOperation(
                retainedJdbc,
                schema,
                UUID.randomUUID(),
                "retained-owner",
                "retained-key-a",
                savedJobId,
                "d".repeat(64));

        migrate(retainedDataSource, schema, null);

        assertEquals(
                1,
                retainedJdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM pg_indexes
                         WHERE schemaname = ?
                           AND indexname =
                               'uq_generation_operation_active_fingerprint'
                        """,
                        Integer.class,
                        schema));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertOperation(
                        retainedJdbc,
                        schema,
                        UUID.randomUUID(),
                        "retained-owner",
                        "retained-key-b",
                        savedJobId,
                        "d".repeat(64)));
    }

    @Test
    void v3FailsClosedWhenRetainedActiveDuplicatesNeedReconciliation() {
        String schema = "duplicates_"
                + UUID.randomUUID().toString().replace("-", "");
        var retainedDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        migrate(retainedDataSource, schema, MigrationVersion.fromVersion("2"));
        var retainedJdbc = new JdbcTemplate(retainedDataSource);
        UUID savedJobId = UUID.randomUUID();
        insertOperation(
                retainedJdbc,
                schema,
                UUID.randomUUID(),
                "duplicate-owner",
                "duplicate-key-a",
                savedJobId,
                "e".repeat(64));
        insertOperation(
                retainedJdbc,
                schema,
                UUID.randomUUID(),
                "duplicate-owner",
                "duplicate-key-b",
                savedJobId,
                "e".repeat(64));

        assertThrows(
                FlywayException.class,
                () -> migrate(retainedDataSource, schema, null));
    }

    @Test
    void v4AddsUploadOperationsWithoutChangingRetainedGenerationData() {
        String schema = "upload_"
                + UUID.randomUUID().toString().replace("-", "");
        var retainedDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        migrate(retainedDataSource, schema, MigrationVersion.fromVersion("3"));
        var retainedJdbc = new JdbcTemplate(retainedDataSource);
        UUID operationId = UUID.randomUUID();
        insertOperation(
                retainedJdbc,
                schema,
                operationId,
                "upload-retained-owner",
                "upload-retained-key",
                UUID.randomUUID(),
                "f".repeat(64));

        migrate(retainedDataSource, schema, null);

        assertEquals(
                1,
                retainedJdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + schema
                                + ".generation_operations WHERE id = ?",
                        Integer.class,
                        operationId));
        assertEquals(
                1,
                retainedJdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                          FROM information_schema.tables
                         WHERE table_schema = ?
                           AND table_name =
                               'application_document_upload_operations'
                        """,
                        Integer.class,
                        schema));
    }

    private void migrate(
            DriverManagerDataSource dataSource,
            String schema,
            MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "classpath:db/migration",
                        "classpath:db/postgresql-migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insertOperation(
            JdbcTemplate targetJdbc,
            String schema,
            UUID id,
            String ownerId,
            String idempotencyKey,
            UUID savedJobId,
            String fingerprint) {
        Instant now = Instant.now();
        targetJdbc.update(
                """
                INSERT INTO %s.generation_operations (
                    id,
                    owner_id,
                    idempotency_key,
                    saved_job_id,
                    request_fingerprint,
                    state,
                    data_json,
                    deadline_at,
                    version,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, 'CREATED', '{}', ?, 0, ?, ?)
                """.formatted(schema),
                id,
                ownerId,
                idempotencyKey,
                savedJobId,
                fingerprint,
                OffsetDateTime.ofInstant(
                        now.plus(Duration.ofMinutes(10)),
                        ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    private DurableGenerationService generationService() {
        return new DurableGenerationService(
                repository,
                downstream,
                objectMapper,
                deadlineGuard,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(1));
    }

    private void successfulDownstream(
            CountDownLatch generationEntered,
            CountDownLatch releaseGeneration) {
        when(downstream.savedJob(SAVED_JOB_ID, AUTHORIZATION))
                .thenReturn(savedJob());
        when(downstream.profile()).thenReturn(Map.of(
                "id", 91,
                "userId", OWNER,
                "revisionId",
                "60000000-0000-4000-8000-000000000091",
                "contentDigest", "f".repeat(64),
                "skills", List.of("Java"),
                "aspirations",
                Map.of("targetRoles", List.of("Backend Developer"))));
        when(downstream.evidenceSnapshot(
                any(DocumentEvidenceSelection.class)))
                .thenAnswer(invocation -> evidenceSnapshot(
                        invocation.getArgument(0)));
        when(downstream.account(AUTHORIZATION)).thenReturn(Map.of(
                "name", "Postgres Candidate",
                "email", "candidate@example.test"));
        when(downstream.estimate(anyString(), anyMap()))
                .thenReturn(1000L);
        when(downstream.reserve(
                anyString(), any(), anyLong()))
                .thenReturn(Map.of(
                        "reservationId",
                        RESERVATION_ID.toString(),
                        "status",
                        "RESERVED"));
        doAnswer(invocation -> {
                    generationEntered.countDown();
                    assertTrue(releaseGeneration.await(
                            5, TimeUnit.SECONDS));
                    return generated(invocation.getArgument(1));
                })
                .when(downstream)
                .generate(anyString(), any(), anyMap());
        when(downstream.createDocument(
                anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> documentResponse(
                        invocation.getArgument(2)));
        when(downstream.approveDocument(
                anyString(), any())).thenAnswer(invocation -> Map.of(
                "id", invocation.getArgument(1).toString(),
                "lifecycleState", "APPROVED"));
        when(downstream.exportDocument(
                anyString(), any(), anyString(), anyMap())).thenAnswer(invocation -> Map.of(
                "documentId",
                invocation.getArgument(1).toString(),
                "exports",
                List.of(
                        Map.of("format", "DOCX"),
                        Map.of("format", "PDF"))));
        when(downstream.createApplication(
                anyString(), anyString(), anyMap()))
                .thenReturn(Map.of(
                        "id", APPLICATION_ID.toString(),
                        "status", "DOCUMENTS_GENERATED"));
    }

    private Map<String, Object> savedJob() {
        return Map.of(
                "savedJobId", SAVED_JOB_ID.toString(),
                "canonicalJobId", "postgres-canonical-job",
                "canonicalSchemaVersion", "2.0",
                "snapshotVersion", 1,
                "contentVersion", "sha256:" + "a".repeat(64),
                "contentSha256", "a".repeat(64),
                "sourceState", "SNAPSHOT",
                "job", Map.ofEntries(
                        Map.entry("id", "postgres-provider-job"),
                        Map.entry("canonicalJobId", "postgres-canonical-job"),
                        Map.entry("provider", "TEST"),
                        Map.entry("externalJobId", "postgres-job"),
                        Map.entry("title", "Backend Developer"),
                        Map.entry("company", "Example Ltd"),
                        Map.entry("advertiserName", "Example Ltd"),
                        Map.entry("advertiserType", "EMPLOYER"),
                        Map.entry("location", "London"),
                        Map.entry("employmentType", "FULL_TIME"),
                        Map.entry("postedDate", "2026-07-01"),
                        Map.entry("description", "Build reliable services."),
                        Map.entry(
                                "descriptionCompleteness",
                                "USER_CONFIRMED")));
    }

    private StartGenerationRequest selectionRequest() {
        return new StartGenerationRequest(List.of(
                new DocumentEvidenceSelection(
                        DocumentPurpose.CV,
                        List.of(CV_EVIDENCE_ID),
                        List.of(EvidenceSection.PROJECT)),
                new DocumentEvidenceSelection(
                        DocumentPurpose.COVER_LETTER,
                        List.of(COVER_LETTER_EVIDENCE_ID),
                        List.of(EvidenceSection.VOLUNTEERING))));
    }

    private Map<String, Object> evidenceSnapshot(
            DocumentEvidenceSelection requested) {
        boolean cv = requested.purpose() == DocumentPurpose.CV;
        var selections = new ArrayList<Map<String, Object>>();
        for (UUID entryId : requested.entryIds()) {
            selections.add(Map.of(
                    "entryId", entryId.toString(),
                    "revisionId",
                    cv
                            ? "70000000-0000-4000-8000-000000000091"
                            : "70000000-0000-4000-8000-000000000092",
                    "revisionNumber", 1,
                    "category",
                    requested.sectionOrder().get(0).name(),
                    "contentDigest", "e".repeat(64),
                    "facts", List.of(Map.of(
                            "factId",
                            cv
                                    ? "80000000-0000-4000-8000-000000000091"
                                    : "80000000-0000-4000-8000-000000000092",
                            "factType", "DESCRIPTION",
                            "factValue", "Grounded experience.",
                            "numericClaim", false))));
        }
        return Map.of(
                "snapshotId",
                cv
                        ? "90000000-0000-4000-8000-000000000091"
                        : "90000000-0000-4000-8000-000000000092",
                "purpose", requested.purpose().name(),
                "profileRevisionId",
                "60000000-0000-4000-8000-000000000091",
                "profileContentDigest", "f".repeat(64),
                "snapshotDigest",
                cv ? "a".repeat(64) : "b".repeat(64),
                "sectionOrder",
                requested.sectionOrder().stream()
                        .map(Enum::name)
                        .toList(),
                "selections",
                selections);
    }

    private Map<String, Object> generated(UUID operationId) {
        return Map.of(
                "operationId", operationId.toString(),
                "inputSchemaVersion", "2.0",
                "cvTitle", "Tailored CV",
                "cvContent", "CV content",
                "coverLetterTitle", "Cover letter",
                "coverLetterContent", "Letter content",
                "generationMetadata", Map.of("releaseId", "release-1"),
                "usage", Map.of(
                        "inputTokens", 400,
                        "outputTokens", 200,
                        "totalTokens", 600),
                "claimLedger", Map.of(
                        "ledgerId",
                        "a0000000-0000-4000-8000-000000000091",
                        "ledgerSha256", "c".repeat(64),
                        "policyVersion", "2.0.0",
                        "parserVersion", "3.0.0",
                        "claims", List.of(Map.of(
                                "claimId", "CLAIM-091",
                                "disposition", "SUPPORTED",
                                "evidenceIds", List.of(
                                        "80000000-0000-4000-8000-000000000091"),
                                "contentPaths",
                                List.of("cv.experience[0]"),
                                "reviewText", "Grounded claim"))),
                "audit", Map.of("modelId", "fixture"));
    }

    private Map<String, Object> documentResponse(
            Map<String, Object> request) {
        boolean cv = "CV".equals(request.get("documentType"));
        return Map.of(
                "id",
                (cv ? CV_DOCUMENT_ID : COVER_LETTER_DOCUMENT_ID)
                        .toString(),
                "documentType", request.get("documentType"),
                "lifecycleState", "DRAFT",
                "contentSha256", "b".repeat(64));
    }
}
