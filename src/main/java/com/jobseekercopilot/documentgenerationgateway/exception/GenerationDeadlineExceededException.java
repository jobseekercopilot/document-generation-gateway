package com.jobseekercopilot.documentgenerationgateway.exception;

public class GenerationDeadlineExceededException extends RuntimeException {
    private final boolean downstreamCallStarted;

    public GenerationDeadlineExceededException(
            boolean downstreamCallStarted,
            Throwable cause) {
        super("The document-generation operation deadline was exceeded.", cause);
        this.downstreamCallStarted = downstreamCallStarted;
    }

    public boolean downstreamCallStarted() {
        return downstreamCallStarted;
    }
}
