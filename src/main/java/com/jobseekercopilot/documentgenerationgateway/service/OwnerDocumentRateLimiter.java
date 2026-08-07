package com.jobseekercopilot.documentgenerationgateway.service;

import com.jobseekercopilot.documentgenerationgateway.exception.OwnerDocumentRateLimitExceededException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OwnerDocumentRateLimiter {
    private static final long WINDOW_SECONDS = 60;

    private final int metadataLimit;
    private final int downloadLimit;
    private final Clock clock;
    private final ConcurrentHashMap<OwnerOperation, WindowCount> windows =
            new ConcurrentHashMap<>();

    @Autowired
    public OwnerDocumentRateLimiter(
            @Value("${document-generation.rate-limit.document-metadata-per-minute:120}")
            int metadataLimit,
            @Value("${document-generation.rate-limit.document-downloads-per-minute:30}")
            int downloadLimit) {
        this(metadataLimit, downloadLimit, Clock.systemUTC());
    }

    OwnerDocumentRateLimiter(
            int metadataLimit, int downloadLimit, Clock clock) {
        if (metadataLimit < 1 || downloadLimit < 1) {
            throw new IllegalArgumentException(
                    "Document rate limits must be positive.");
        }
        this.metadataLimit = metadataLimit;
        this.downloadLimit = downloadLimit;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void metadata(String ownerId) {
        acquire(ownerId, Operation.METADATA, metadataLimit);
    }

    public void download(String ownerId) {
        acquire(ownerId, Operation.DOWNLOAD, downloadLimit);
    }

    private void acquire(String ownerId, Operation operation, int limit) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated document owner is required.");
        }
        long now = Instant.now(clock).getEpochSecond();
        long window = now / WINDOW_SECONDS;
        WindowCount count = windows.compute(
                new OwnerOperation(ownerId, operation),
                (key, existing) -> existing == null || existing.window() != window
                        ? new WindowCount(window, 1)
                        : new WindowCount(window, existing.count() + 1));
        if (count.count() > limit) {
            throw new OwnerDocumentRateLimitExceededException(
                    WINDOW_SECONDS - (now % WINDOW_SECONDS));
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry ->
                    entry.getValue().window() < window);
        }
    }

    private enum Operation {
        METADATA,
        DOWNLOAD
    }

    private record OwnerOperation(String ownerId, Operation operation) {
    }

    private record WindowCount(long window, int count) {
    }
}
