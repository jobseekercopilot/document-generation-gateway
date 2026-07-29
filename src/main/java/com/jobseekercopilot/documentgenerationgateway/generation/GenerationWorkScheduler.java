package com.jobseekercopilot.documentgenerationgateway.generation;

import java.time.Duration;
import java.util.UUID;

public interface GenerationWorkScheduler {
    boolean submit(
            UUID operationId,
            String correlationId,
            Runnable work);

    boolean submitAfter(
            UUID operationId,
            String correlationId,
            Duration delay,
            Runnable work);
}
