package com.jobseekercopilot.documentgenerationgateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import com.jobseekercopilot.generated.documentexportservice.client.auth.ApiKeyAuth;
import org.junit.jupiter.api.Test;

class DownstreamApiConfigTest {

    @Test
    void bindsDocumentExportClientToItsDedicatedServiceCredential() {
        String documentExportToken =
                "test-only-document-export-service-token-32-bytes";
        DownstreamServiceCredentials credentials = new DownstreamServiceCredentials(
                "test-only-authentication-service-token-32-bytes",
                "test-only-application-producer-token-32-bytes",
                documentExportToken,
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes");

        var api = new DownstreamApiConfig()
                .documentExportsApi("http://document-export", credentials);
        var authentication = (ApiKeyAuth) api.getApiClient()
                .getAuthentication("serviceToken");

        assertEquals("http://document-export", api.getApiClient().getBasePath());
        assertEquals(documentExportToken, authentication.getApiKey());
        assertEquals("X-Service-Token", authentication.getParamName());
    }
}
