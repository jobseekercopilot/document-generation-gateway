package com.jobseekercopilot.documentgenerationgateway.exception;

public class GenerationNotFoundException extends RuntimeException {
    public GenerationNotFoundException() {
        super("Generation operation was not found.");
    }
}
