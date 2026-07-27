package com.jobseekercopilot.documentgenerationgateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DownstreamServiceCredentialsTest {

    private static final String AUTHENTICATION =
            "test-only-authentication-service-token-32-bytes";
    private static final String APPLICATION_TRACKER =
            "test-only-application-producer-token-32-bytes";
    private static final String CV_COVER_LETTER =
            "test-only-cv-cover-letter-service-token-32-bytes";
    private static final String DOCUMENT_EXPORT =
            "test-only-document-export-service-token-32-bytes";
    private static final String DOCUMENT_STORE_PRODUCER =
            "test-only-document-store-producer-token-32-bytes";
    private static final String DOCUMENT_STORE_READER =
            "test-only-document-store-reader-token-32-bytes";
    private static final String PAYMENT =
            "test-only-payment-service-token-0000000000001";

    @Test
    void acceptsDistinctRuntimeInjectedCredentials() {
        DownstreamServiceCredentials credentials =
                new DownstreamServiceCredentials(
                        AUTHENTICATION,
                        APPLICATION_TRACKER,
                        CV_COVER_LETTER,
                        DOCUMENT_EXPORT,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER,
                        PAYMENT);

        assertEquals(AUTHENTICATION, credentials.authenticationServiceToken());
        assertEquals(APPLICATION_TRACKER, credentials.applicationTrackerProducerToken());
        assertEquals(CV_COVER_LETTER, credentials.cvCoverLetterServiceToken());
        assertEquals(DOCUMENT_EXPORT, credentials.documentExportServiceToken());
        assertEquals(DOCUMENT_STORE_PRODUCER, credentials.documentStoreProducerToken());
        assertEquals(DOCUMENT_STORE_READER, credentials.documentStoreReaderToken());
        assertEquals(PAYMENT, credentials.paymentServiceToken());
    }

    @Test
    void rejectsMissingShortOrSharedCredentialsWithoutReflectingValues() {
        IllegalStateException blank = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        "",
                        APPLICATION_TRACKER,
                        CV_COVER_LETTER,
                        DOCUMENT_EXPORT,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER,
                        PAYMENT));
        IllegalStateException shortToken = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        "short",
                        APPLICATION_TRACKER,
                        CV_COVER_LETTER,
                        DOCUMENT_EXPORT,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER,
                        PAYMENT));
        IllegalStateException missingCv = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        AUTHENTICATION,
                        APPLICATION_TRACKER,
                        "",
                        DOCUMENT_EXPORT,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER,
                        PAYMENT));
        IllegalStateException missingExport = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        AUTHENTICATION,
                        APPLICATION_TRACKER,
                        CV_COVER_LETTER,
                        "",
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER,
                        PAYMENT));
        IllegalStateException sharedCv = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        AUTHENTICATION,
                        APPLICATION_TRACKER,
                        DOCUMENT_EXPORT,
                        DOCUMENT_EXPORT,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER,
                        PAYMENT));
        IllegalStateException sharedPayment = assertThrows(
                IllegalStateException.class,
                () -> new DownstreamServiceCredentials(
                        AUTHENTICATION,
                        APPLICATION_TRACKER,
                        CV_COVER_LETTER,
                        DOCUMENT_EXPORT,
                        DOCUMENT_STORE_PRODUCER,
                        DOCUMENT_STORE_READER,
                        DOCUMENT_STORE_READER));

        assertEquals(
                "Authentication service token must contain at least 32 bytes.",
                blank.getMessage());
        assertEquals(blank.getMessage(), shortToken.getMessage());
        assertEquals(
                "CV and Cover Letter service token must contain at least 32 bytes.",
                missingCv.getMessage());
        assertEquals(
                "Document Export service token must contain at least 32 bytes.",
                missingExport.getMessage());
        assertEquals(
                "Downstream service identity tokens must be distinct.",
                sharedCv.getMessage());
        assertEquals(sharedCv.getMessage(), sharedPayment.getMessage());
    }
}
