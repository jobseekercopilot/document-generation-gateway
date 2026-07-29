package com.jobseekercopilot.documentgenerationgateway.generation;

import com.jobseekercopilot.documentgenerationgateway.logging.CorrelationIdFilter;
import com.jobseekercopilot.documentgenerationgateway.logging.CorrelationIds;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class BoundedGenerationWorkScheduler
        implements GenerationWorkScheduler {
    private static final Logger log =
            LoggerFactory.getLogger(BoundedGenerationWorkScheduler.class);

    private final AsyncTaskExecutor executor;
    private final TaskScheduler retryScheduler;
    private final String serviceName;
    private final int deferredCapacity;
    private final Map<UUID, DeferredWork> deferred =
            new ConcurrentHashMap<>();
    private final Set<UUID> deferredDue =
            ConcurrentHashMap.newKeySet();
    private final Set<UUID> active =
            ConcurrentHashMap.newKeySet();
    private final Map<UUID, DeferredWork> pending =
            new ConcurrentHashMap<>();

    public BoundedGenerationWorkScheduler(
            @Qualifier("generationWorkExecutor")
            AsyncTaskExecutor executor,
            @Qualifier("generationRetryScheduler")
            TaskScheduler retryScheduler,
            @Value("${spring.application.name:document-generation-gateway}")
            String serviceName,
            @Value("${document-generation.executor.deferred-capacity:64}")
            int deferredCapacity) {
        if (deferredCapacity < 1) {
            throw new IllegalStateException(
                    "Document generation deferred capacity must be positive.");
        }
        this.executor = executor;
        this.retryScheduler = retryScheduler;
        this.serviceName = serviceName;
        this.deferredCapacity = deferredCapacity;
    }

    @Override
    public boolean submit(
            UUID operationId,
            String correlationId,
            Runnable work) {
        String safeCorrelationId = CorrelationIds.normalize(correlationId);
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        synchronized (deferred) {
            if (active.contains(operationId)) {
                pending.put(
                        operationId,
                        new DeferredWork(
                                safeCorrelationId,
                                authentication,
                                work));
                return true;
            }
            if (deferred.containsKey(operationId)) {
                return true;
            }
            active.add(operationId);
        }
        return dispatchReserved(
                operationId, safeCorrelationId, authentication, work);
    }

    @Override
    public boolean submitAfter(
            UUID operationId,
            String correlationId,
            Duration delay,
            Runnable work) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return submit(operationId, correlationId, work);
        }
        String safeCorrelationId = CorrelationIds.normalize(correlationId);
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        synchronized (deferred) {
            if (deferred.containsKey(operationId)) {
                return true;
            }
            if (deferred.size() >= deferredCapacity) {
                log.warn(
                        "generation retry deferred operationId={} "
                                + "correlationId={} "
                                + "reason=DEFERRED_CAPACITY",
                        operationId,
                        safeCorrelationId);
                return false;
            }
            DeferredWork deferredWork = new DeferredWork(
                    safeCorrelationId, authentication, work);
            deferred.put(operationId, deferredWork);
            try {
                var scheduled = retryScheduler.schedule(
                        () -> dispatchDeferred(
                                operationId, deferredWork),
                        Instant.now().plus(delay));
                if (scheduled == null) {
                    deferred.remove(operationId, deferredWork);
                    return false;
                }
                return true;
            } catch (TaskRejectedException rejected) {
                deferred.remove(operationId, deferredWork);
                log.warn(
                        "generation retry deferred operationId={} "
                                + "correlationId={} "
                                + "reason=RETRY_SCHEDULER_CAPACITY",
                        operationId,
                        safeCorrelationId);
                return false;
            }
        }
    }

    private void dispatchDeferred(
            UUID operationId,
            DeferredWork expected) {
        synchronized (deferred) {
            if (deferred.get(operationId) != expected) {
                return;
            }
            if (active.contains(operationId)) {
                deferredDue.add(operationId);
                return;
            }
            deferred.remove(operationId);
            deferredDue.remove(operationId);
            active.add(operationId);
        }
        dispatchReserved(
                operationId,
                expected.correlationId(),
                expected.authentication(),
                expected.work());
    }

    private boolean dispatchReserved(
            UUID operationId,
            String safeCorrelationId,
            Authentication authentication,
            Runnable work) {
        try {
            executor.execute(() -> runWithContext(
                    operationId,
                    safeCorrelationId,
                    authentication,
                    work));
            return true;
        } catch (TaskRejectedException rejected) {
            active.remove(operationId);
            log.warn(
                    "generation work deferred operationId={} "
                            + "correlationId={} reason=EXECUTOR_CAPACITY",
                    operationId,
                    safeCorrelationId);
            return false;
        }
    }

    private void runWithContext(
            UUID operationId,
            String correlationId,
            Authentication authentication,
            Runnable work) {
        SecurityContext previousSecurityContext =
                SecurityContextHolder.getContext();
        var previousMdc = MDC.getCopyOfContextMap();
            SecurityContext workerSecurityContext =
                SecurityContextHolder.createEmptyContext();
        workerSecurityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(workerSecurityContext);
        MDC.clear();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        MDC.put(CorrelationIdFilter.SERVICE_MDC_KEY, serviceName);
        try {
            work.run();
        } catch (RuntimeException failure) {
            log.error(
                    "generation work failed unexpectedly operationId={} "
                            + "correlationId={} failureType={}",
                    operationId,
                    correlationId,
                    failure.getClass().getSimpleName());
        } finally {
            DeferredWork due = releaseAndTakeDue(operationId);
            SecurityContextHolder.setContext(previousSecurityContext);
            MDC.clear();
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            }
            if (due != null) {
                dispatchReserved(
                        operationId,
                        due.correlationId(),
                        due.authentication(),
                        due.work());
            }
        }
    }

    private DeferredWork releaseAndTakeDue(UUID operationId) {
        synchronized (deferred) {
            active.remove(operationId);
            DeferredWork next = null;
            if (deferredDue.remove(operationId)) {
                next = deferred.remove(operationId);
            } else if (!deferred.containsKey(operationId)) {
                next = pending.get(operationId);
            }
            pending.remove(operationId);
            if (next != null) {
                active.add(operationId);
            }
            return next;
        }
    }

    private record DeferredWork(
            String correlationId,
            Authentication authentication,
            Runnable work) {
    }
}
