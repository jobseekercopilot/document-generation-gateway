package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.documentgenerationgateway.logging.CorrelationIdFilter;
import com.jobseekercopilot.documentgenerationgateway.logging.CorrelationIds;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.TaskScheduler;

class BoundedGenerationWorkSchedulerTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void propagatesOnlyValidatedCorrelationAndAuthenticationContext() {
        var authentication =
                new TestingAuthenticationToken("owner-1", "ignored");
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext()
                .setAuthentication(authentication);
        TaskScheduler retryScheduler = mock(TaskScheduler.class);
        var scheduler = new BoundedGenerationWorkScheduler(
                new TaskExecutorAdapter(Runnable::run),
                retryScheduler,
                "document-generation-gateway",
                4);

        boolean submitted = scheduler.submit(
                UUID.randomUUID(),
                "request-123",
                () -> {
                    assertEquals(
                            "request-123",
                            MDC.get(CorrelationIdFilter.MDC_KEY));
                    assertEquals(
                            authentication,
                            SecurityContextHolder.getContext()
                                    .getAuthentication());
                });

        assertTrue(submitted);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
        assertEquals(
                authentication,
                SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rejectsExcessWorkWithoutLosingThePersistedOperation() {
        Executor rejectingExecutor = work -> {
            throw new TaskRejectedException("full");
        };
        var scheduler = new BoundedGenerationWorkScheduler(
                new TaskExecutorAdapter(rejectingExecutor),
                mock(TaskScheduler.class),
                "document-generation-gateway",
                4);

        boolean submitted = scheduler.submit(
                UUID.randomUUID(),
                "candidate@example.com is not a correlation id",
                () -> {
                    throw new AssertionError("work must not run");
                });

        assertFalse(submitted);
        assertTrue(CorrelationIds.isValid(
                CorrelationIds.normalize("unsafe value")));
    }

    @Test
    void coalescesRepeatedSubmissionsUntilTheScheduledWorkFinishes() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger executorSubmissions = new AtomicInteger();
        Executor holdingExecutor = work -> {
            executorSubmissions.incrementAndGet();
            queued.set(work);
        };
        var scheduler = new BoundedGenerationWorkScheduler(
                new TaskExecutorAdapter(holdingExecutor),
                mock(TaskScheduler.class),
                "document-generation-gateway",
                4);
        UUID operationId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertTrue(scheduler.submit(
                operationId,
                "poll-123",
                () -> runs.addAndGet(1)));
        assertTrue(scheduler.submit(
                operationId,
                "poll-123",
                () -> runs.addAndGet(10)));
        assertTrue(scheduler.submit(
                operationId,
                "poll-123",
                () -> runs.addAndGet(100)));
        assertEquals(1, executorSubmissions.get());

        Runnable first = queued.get();
        first.run();
        assertEquals(1, runs.get());
        assertEquals(2, executorSubmissions.get());
        queued.get().run();
        assertEquals(101, runs.get());

        assertTrue(scheduler.submit(
                operationId,
                "later-replay-123",
                () -> runs.addAndGet(1000)));
        assertEquals(3, executorSubmissions.get());
        queued.get().run();
        assertEquals(1101, runs.get());
    }

    @Test
    void rejectedSubmissionReleasesTheOperationForLaterReplay() {
        AtomicInteger attempts = new AtomicInteger();
        Executor rejectOnce = work -> {
            if (attempts.getAndIncrement() == 0) {
                throw new TaskRejectedException("full");
            }
            work.run();
        };
        var scheduler = new BoundedGenerationWorkScheduler(
                new TaskExecutorAdapter(rejectOnce),
                mock(TaskScheduler.class),
                "document-generation-gateway",
                4);
        UUID operationId = UUID.randomUUID();
        AtomicBoolean ran = new AtomicBoolean();

        assertFalse(scheduler.submit(
                operationId, "capacity-123", () -> ran.set(true)));
        assertTrue(scheduler.submit(
                operationId, "capacity-123", () -> ran.set(true)));

        assertTrue(ran.get());
    }

    @Test
    void schedulesOneBoundedDeferredHandoff() {
        TaskScheduler retryScheduler = mock(TaskScheduler.class);
        AtomicReference<Runnable> delayed = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(retryScheduler.schedule(
                any(Runnable.class),
                any(java.time.Instant.class)))
                .thenAnswer(invocation -> {
                    delayed.set(invocation.getArgument(0));
                    return future;
                });
        AtomicBoolean ran = new AtomicBoolean();
        UUID operationId = UUID.randomUUID();
        var scheduler = new BoundedGenerationWorkScheduler(
                new TaskExecutorAdapter(Runnable::run),
                retryScheduler,
                "document-generation-gateway",
                1);

        assertTrue(scheduler.submitAfter(
                operationId,
                "restart-123",
                Duration.ofSeconds(1),
                () -> ran.set(true)));
        assertTrue(scheduler.submitAfter(
                operationId,
                "restart-123",
                Duration.ofSeconds(1),
                () -> {
                    throw new AssertionError(
                            "duplicate retry must be coalesced");
                }));
        assertTrue(scheduler.submit(
                operationId,
                "restart-123",
                () -> {
                    throw new AssertionError(
                            "poll must not bypass a deferred handoff");
                }));
        assertFalse(ran.get());

        delayed.get().run();

        assertTrue(ran.get());
    }

    @Test
    void activeWorkCanArrangeOneDeferredReplayForItself() {
        TaskScheduler retryScheduler = mock(TaskScheduler.class);
        AtomicReference<Runnable> delayed = new AtomicReference<>();
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(retryScheduler.schedule(
                any(Runnable.class),
                any(java.time.Instant.class)))
                .thenAnswer(invocation -> {
                    delayed.set(invocation.getArgument(0));
                    return future;
                });
        AtomicReference<Runnable> queued = new AtomicReference<>();
        var schedulerReference =
                new AtomicReference<BoundedGenerationWorkScheduler>();
        UUID operationId = UUID.randomUUID();
        AtomicBoolean replayed = new AtomicBoolean();
        var scheduler = new BoundedGenerationWorkScheduler(
                new TaskExecutorAdapter(queued::set),
                retryScheduler,
                "document-generation-gateway",
                1);
        schedulerReference.set(scheduler);

        assertTrue(scheduler.submit(
                operationId,
                "lease-123",
                () -> {
                    assertTrue(schedulerReference.get().submitAfter(
                            operationId,
                            "lease-123",
                            Duration.ofSeconds(1),
                            () -> replayed.set(true)));
                    delayed.get().run();
                    assertFalse(replayed.get());
                }));
        queued.get().run();
        queued.get().run();

        assertTrue(replayed.get());
    }
}
