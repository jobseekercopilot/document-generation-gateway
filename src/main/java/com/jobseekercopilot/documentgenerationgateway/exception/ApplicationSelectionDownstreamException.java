package com.jobseekercopilot.documentgenerationgateway.exception;

import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionsResponse;
import org.springframework.http.HttpStatusCode;

public class ApplicationSelectionDownstreamException extends RuntimeException {
    private final HttpStatusCode status;
    private final ApplicationDocumentSelectionsResponse currentApplication;

    public ApplicationSelectionDownstreamException(
            HttpStatusCode status,
            ApplicationDocumentSelectionsResponse currentApplication) {
        super("Application selection request failed.");
        this.status = status;
        this.currentApplication = currentApplication;
    }

    public HttpStatusCode status() {
        return status;
    }

    public ApplicationDocumentSelectionsResponse currentApplication() {
        return currentApplication;
    }
}
