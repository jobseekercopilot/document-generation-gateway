package com.jobseekercopilot.documentgenerationgateway.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobseekercopilot.documentgenerationgateway.exception.OwnerDocumentRateLimitExceededException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OwnerDocumentRateLimiterTest {
    @Test
    void limitsEachAuthenticatedOwnerAndOperationIndependently() {
        var limiter = new OwnerDocumentRateLimiter(
                2,
                1,
                Clock.fixed(Instant.parse("2026-08-07T10:00:30Z"), ZoneOffset.UTC));

        limiter.metadata("alice");
        limiter.metadata("alice");
        assertDoesNotThrow(() -> limiter.metadata("bob"));
        assertDoesNotThrow(() -> limiter.download("alice"));

        OwnerDocumentRateLimitExceededException metadata = assertThrows(
                OwnerDocumentRateLimitExceededException.class,
                () -> limiter.metadata("alice"));
        OwnerDocumentRateLimitExceededException download = assertThrows(
                OwnerDocumentRateLimitExceededException.class,
                () -> limiter.download("alice"));
        assertEquals(30, metadata.retryAfterSeconds());
        assertEquals(30, download.retryAfterSeconds());
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OwnerDocumentRateLimiter(
                        0, 1, Clock.systemUTC()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OwnerDocumentRateLimiter(
                        1, 0, Clock.systemUTC()));
    }
}
