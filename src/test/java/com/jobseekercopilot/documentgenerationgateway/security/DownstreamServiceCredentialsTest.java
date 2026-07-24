package com.jobseekercopilot.documentgenerationgateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DownstreamServiceCredentialsTest {

    private static final String AUTHENTICATION =
            "test-only-authentication-service-token-32-bytes";
    private static final String APPLICATION_TRACKER =
            "test-only-application-producer-token-32-bytes";
    private static final String DOCUMENT_STORE_PRODUCER =
            "test-only-document-store-producer-token-32-bytes";
    private static final String DOCUMENT_STORE_READER =
            "test-only-document-store-reader-token-32-bytes";

    @Test
    void acceptsDistinctRuntimeInjectedCredentials() {
        DownstreamServiceCredentials credentials =
                new DownstreamServiceCredentials(
                        AUTHENTICATION,
                        APPLICATION_TRACKER,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER);

        assertEquals(AUTHENTICATION, credentials.authenticationServiceToken());
        assertEquals(APPLICATION_TRACKER, credentials.applicationTrackerProducerToken());
        assertEquals(DOCUMENT_STORE_PRODUCER, credentials.documentStoreProducerToken());
        assertEquals(DOCUMENT_STORE_READER, credentials.documentStoreReaderToken());
    }

    @Test
    void rejectsMissingShortOrSharedCredentialsWithoutReflectingValues() {
        IllegalStateException blank = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        "",
                        APPLICATION_TRACKER,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER));
        IllegalStateException shortToken = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        "short",
                        APPLICATION_TRACKER,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER));
        IllegalStateException shared = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        AUTHENTICATION,
                        APPLICATION_TRACKER,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_PRODUCER));

        assertEquals(
                "Authentication service token must contain at least 32 bytes.",
                blank.getMessage());
        assertEquals(blank.getMessage(), shortToken.getMessage());
        assertEquals("Downstream service identity tokens must be distinct.", shared.getMessage());
    }
}
