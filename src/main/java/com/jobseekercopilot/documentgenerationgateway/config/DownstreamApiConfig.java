package com.jobseekercopilot.documentgenerationgateway.config;

import com.jobseekercopilot.generated.cvcoverletterservice.api.CvCoverLetterControllerApi;
import com.jobseekercopilot.generated.documentexportservice.api.DocumentExportsApi;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import com.jobseekercopilot.documentgenerationgateway.security.CurrentAccessTokenSupplier;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
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
            CurrentAccessTokenSupplier accessTokenSupplier) {
        var client = new com.jobseekercopilot.generated.userprofileservice.client.ApiClient();
        client.setBasePath(baseUrl);
        client.setBearerToken(accessTokenSupplier);
        return new UserProfilesApi(client);
    }

    @Bean
    CvCoverLetterControllerApi cvCoverLetterApi(
            @Value("${services.cv-cover-letter-service.base-url}") String baseUrl,
            DownstreamServiceCredentials credentials) {
        var client = new com.jobseekercopilot.generated.cvcoverletterservice.client.ApiClient();
        client.setBasePath(baseUrl);
        client.setApiKey(credentials.cvCoverLetterServiceToken());
        return new CvCoverLetterControllerApi(client);
    }

    @Bean
    DocumentExportsApi documentExportsApi(
            @Value("${services.document-export-service.base-url}") String baseUrl,
            DownstreamServiceCredentials credentials) {
        var client = new com.jobseekercopilot.generated.documentexportservice.client.ApiClient();
        client.setBasePath(baseUrl);
        client.setApiKey(credentials.documentExportServiceToken());
        return new DocumentExportsApi(client);
    }

    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .requestFactory(() -> new HttpComponentsClientHttpRequestFactory())
                .build();
    }
}
