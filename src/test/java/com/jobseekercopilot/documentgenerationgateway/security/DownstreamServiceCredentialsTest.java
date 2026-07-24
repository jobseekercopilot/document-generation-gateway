package com.jobseekercopilot.documentgenerationgateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DownstreamServiceCredentialsTest {

    private static final String AUTHENTICATION =
            "test-only-authentication-service-token-32-bytes";
    private static final String APPLICATION_TRACKER =
            "test-only-application-producer-token-32-bytes";

    @Test
    void acceptsDistinctRuntimeInjectedCredentials() {
        DownstreamServiceCredentials credentials =
                new DownstreamServiceCredentials(AUTHENTICATION, APPLICATION_TRACKER);

        assertEquals(AUTHENTICATION, credentials.authenticationServiceToken());
        assertEquals(APPLICATION_TRACKER, credentials.applicationTrackerProducerToken());
    }

    @Test
    void rejectsMissingShortOrSharedCredentialsWithoutReflectingValues() {
        IllegalStateException blank = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials("", APPLICATION_TRACKER));
        IllegalStateException shortToken = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials("short", APPLICATION_TRACKER));
        IllegalStateException shared = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(AUTHENTICATION, AUTHENTICATION));

        assertEquals(
                "Authentication service token must contain at least 32 bytes.",
                blank.getMessage());
        assertEquals(blank.getMessage(), shortToken.getMessage());
        assertEquals("Downstream service identity tokens must be distinct.", shared.getMessage());
    }
}
