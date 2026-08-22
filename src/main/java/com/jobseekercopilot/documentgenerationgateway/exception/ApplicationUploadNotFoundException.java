package com.jobseekercopilot.documentgenerationgateway.exception;

public class ApplicationUploadNotFoundException extends RuntimeException {
    public ApplicationUploadNotFoundException() {
        super("Application upload operation or saved application was not found.");
    }
}
