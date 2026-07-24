package com.jobseekercopilot.documentgenerationgateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DownstreamServiceCredentials {

    static final int MINIMUM_TOKEN_BYTES = 32;

    private final String authenticationServiceToken;
    private final String applicationTrackerProducerToken;

    public DownstreamServiceCredentials(
            @Value("${document-generation.security.authentication-service-token}")
            String authenticationServiceToken,
            @Value("${document-generation.security.application-tracker-producer-token}")
            String applicationTrackerProducerToken) {
        this.authenticationServiceToken = validate(
                authenticationServiceToken,
                "Authentication service token");
        this.applicationTrackerProducerToken = validate(
                applicationTrackerProducerToken,
                "Application Tracker producer token");
        if (MessageDigest.isEqual(
                authenticationServiceToken.getBytes(StandardCharsets.UTF_8),
                applicationTrackerProducerToken.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("Downstream service identity tokens must be distinct.");
        }
    }

    public String authenticationServiceToken() {
        return authenticationServiceToken;
    }

    public String applicationTrackerProducerToken() {
        return applicationTrackerProducerToken;
    }

    private static String validate(String value, String label) {
        if (value == null
                || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length < MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(label + " must contain at least 32 bytes.");
        }
        return value;
    }
}
