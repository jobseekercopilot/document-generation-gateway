package com.jobseekercopilot.documentgenerationgateway.exception;

public class ApplicationUploadTooLargeException extends RuntimeException {
    public ApplicationUploadTooLargeException() {
        super("Uploaded application document must be 10 MiB or less.");
    }
}
