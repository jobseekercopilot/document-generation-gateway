package com.jobseekercopilot.documentgenerationgateway.exception;

import org.springframework.http.HttpStatusCode;

public class DocumentHistoryDownstreamException extends RuntimeException {
    private final HttpStatusCode status;

    public DocumentHistoryDownstreamException(HttpStatusCode status) {
        super("Document history request failed.");
        this.status = status;
    }

    public HttpStatusCode status() {
        return status;
    }
}
