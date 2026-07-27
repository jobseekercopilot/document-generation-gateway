package com.jobseekercopilot.documentgenerationgateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobseekercopilot.documentgenerationgateway.generation.OperationDeadlineGuard;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import com.jobseekercopilot.generated.documentexportservice.client.auth.ApiKeyAuth;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

class DownstreamApiConfigTest {

    @Test
    void bindsGeneratedClientsToTheirDedicatedServiceCredentials() {
        String cvCoverLetterToken =
                "test-only-cv-cover-letter-service-token-32-bytes";
        String documentExportToken =
                "test-only-document-export-service-token-32-bytes";
        DownstreamServiceCredentials credentials = new DownstreamServiceCredentials(
                "test-only-authentication-service-token-32-bytes",
                "test-only-application-producer-token-32-bytes",
                cvCoverLetterToken,
                documentExportToken,
                "test-only-document-store-producer-token-32-bytes",
                "test-only-document-store-reader-token-32-bytes",
                "test-only-payment-service-token-0000000000001");

        var config = new DownstreamApiConfig();
        var restTemplate = new RestTemplate();
        var cvApi = config.cvCoverLetterApi(
                "http://cv-cover-letter", credentials, restTemplate);
        var cvAuthentication =
                (com.jobseekercopilot.generated.cvcoverletterservice.client.auth.ApiKeyAuth)
                        cvApi.getApiClient().getAuthentication("serviceToken");
        var api = config
                .documentExportsApi(
                        "http://document-export",
                        credentials,
                        restTemplate);
        var authentication = (ApiKeyAuth) api.getApiClient()
                .getAuthentication("serviceToken");

        assertEquals("http://cv-cover-letter", cvApi.getApiClient().getBasePath());
        assertEquals(cvCoverLetterToken, cvAuthentication.getApiKey());
        assertEquals("X-Service-Token", cvAuthentication.getParamName());
        assertEquals("http://document-export", api.getApiClient().getBasePath());
        assertEquals(documentExportToken, authentication.getApiKey());
        assertEquals("X-Service-Token", authentication.getParamName());
    }

    @Test
    void rejectsUnboundedOrInvalidStaticHttpTimeouts() {
        var config = new DownstreamApiConfig();
        var builder = new RestTemplateBuilder();
        var guard = new OperationDeadlineGuard();

        assertThrows(
                IllegalStateException.class,
                () -> config.restTemplate(
                        builder,
                        guard,
                        Duration.ZERO,
                        Duration.ofSeconds(1)));
        assertThrows(
                IllegalStateException.class,
                () -> config.restTemplate(
                        builder,
                        guard,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(-1)));
    }
}
