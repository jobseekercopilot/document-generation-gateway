package com.jobseekercopilot.documentgenerationgateway.config;

import com.jobseekercopilot.generated.cvcoverletterservice.api.CvCoverLetterControllerApi;
import com.jobseekercopilot.generated.documentexportservice.api.DocumentExportsApi;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import com.jobseekercopilot.generated.userprofileservice.api.EvidenceSnapshotsApi;
import com.jobseekercopilot.documentgenerationgateway.generation.OperationDeadlineGuard;
import com.jobseekercopilot.documentgenerationgateway.security.CurrentAccessTokenSupplier;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import java.time.Duration;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class DownstreamApiConfig {
    @Bean
    UserProfilesApi userProfilesApi(
            @Value("${services.user-profile-service.base-url}") String baseUrl,
            CurrentAccessTokenSupplier accessTokenSupplier,
            RestTemplate restTemplate) {
        var client = new com.jobseekercopilot.generated.userprofileservice.client.ApiClient(
                restTemplate);
        client.setBasePath(baseUrl);
        client.setBearerToken(accessTokenSupplier);
        return new UserProfilesApi(client);
    }

    @Bean
    EvidenceSnapshotsApi evidenceSnapshotsApi(
            @Value("${services.user-profile-service.base-url}") String baseUrl,
            CurrentAccessTokenSupplier accessTokenSupplier,
            RestTemplate restTemplate) {
        var client = new com.jobseekercopilot.generated.userprofileservice.client.ApiClient(
                restTemplate);
        client.setBasePath(baseUrl);
        client.setBearerToken(accessTokenSupplier);
        return new EvidenceSnapshotsApi(client);
    }

    @Bean
    CvCoverLetterControllerApi cvCoverLetterApi(
            @Value("${services.cv-cover-letter-service.base-url}") String baseUrl,
            DownstreamServiceCredentials credentials,
            RestTemplate restTemplate) {
        var client = new com.jobseekercopilot.generated.cvcoverletterservice.client.ApiClient(
                restTemplate);
        client.setBasePath(baseUrl);
        client.setApiKey(credentials.cvCoverLetterServiceToken());
        return new CvCoverLetterControllerApi(client);
    }

    @Bean
    DocumentExportsApi documentExportsApi(
            @Value("${services.document-export-service.base-url}") String baseUrl,
            DownstreamServiceCredentials credentials,
            RestTemplate restTemplate) {
        var client = new com.jobseekercopilot.generated.documentexportservice.client.ApiClient(
                restTemplate);
        client.setBasePath(baseUrl);
        client.setApiKey(credentials.documentExportServiceToken());
        return new DocumentExportsApi(client);
    }

    @Bean
    RestTemplate restTemplate(
            RestTemplateBuilder builder,
            OperationDeadlineGuard deadlineGuard,
            @Value("${document-generation.downstream.connect-timeout}")
            Duration connectTimeout,
            @Value("${document-generation.downstream.read-timeout}")
            Duration readTimeout) {
        requirePositive(connectTimeout, "Downstream connect timeout");
        requirePositive(readTimeout, "Downstream read timeout");
        var requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setConnectionRequestTimeout(connectTimeout);
        requestFactory.setHttpContextFactory((method, uri) -> {
            Duration boundedConnect =
                    deadlineGuard.remainingOr(connectTimeout);
            Duration boundedResponse =
                    deadlineGuard.remainingOr(readTimeout);
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(timeout(boundedConnect))
                    .setConnectTimeout(timeout(boundedConnect))
                    .setResponseTimeout(timeout(boundedResponse))
                    .build();
            HttpClientContext context = HttpClientContext.create();
            context.setRequestConfig(requestConfig);
            return context;
        });
        return builder
                .requestFactory(() -> requestFactory)
                .build();
    }

    private static Timeout timeout(Duration duration) {
        return Timeout.ofMilliseconds(
                Math.max(1L, duration.toMillis()));
    }

    private static void requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(label + " must be positive.");
        }
    }
}
