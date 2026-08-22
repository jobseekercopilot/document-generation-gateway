package com.jobseekercopilot.documentgenerationgateway.exception;

public class OwnerDocumentRateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public OwnerDocumentRateLimitExceededException(long retryAfterSeconds) {
        super("Owner document request rate limit exceeded.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
