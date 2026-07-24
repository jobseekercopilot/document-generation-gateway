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
    private final String documentStoreProducerToken;
    private final String documentStoreReaderToken;

    public DownstreamServiceCredentials(
            @Value("${document-generation.security.authentication-service-token}")
            String authenticationServiceToken,
            @Value("${document-generation.security.application-tracker-producer-token}")
            String applicationTrackerProducerToken,
            @Value("${document-generation.security.document-store-producer-token}")
            String documentStoreProducerToken,
            @Value("${document-generation.security.document-store-reader-token}")
            String documentStoreReaderToken) {
        this.authenticationServiceToken = validate(
                authenticationServiceToken,
                "Authentication service token");
        this.applicationTrackerProducerToken = validate(
                applicationTrackerProducerToken,
                "Application Tracker producer token");
        this.documentStoreProducerToken = validate(
                documentStoreProducerToken,
                "Document Store producer token");
        this.documentStoreReaderToken = validate(
                documentStoreReaderToken,
                "Document Store reader token");
        requireDistinct(
                authenticationServiceToken,
                applicationTrackerProducerToken,
                documentStoreProducerToken,
                documentStoreReaderToken);
    }

    public String authenticationServiceToken() {
        return authenticationServiceToken;
    }

    public String applicationTrackerProducerToken() {
        return applicationTrackerProducerToken;
    }

    public String documentStoreProducerToken() {
        return documentStoreProducerToken;
    }

    public String documentStoreReaderToken() {
        return documentStoreReaderToken;
    }

    private static String validate(String value, String label) {
        if (value == null
                || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length < MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(label + " must contain at least 32 bytes.");
        }
        return value;
    }

    private static void requireDistinct(String... values) {
        for (int first = 0; first < values.length; first++) {
            for (int second = first + 1; second < values.length; second++) {
                if (MessageDigest.isEqual(
                        values[first].getBytes(StandardCharsets.UTF_8),
                        values[second].getBytes(StandardCharsets.UTF_8))) {
                    throw new IllegalStateException(
                            "Downstream service identity tokens must be distinct.");
                }
            }
        }
    }
}
