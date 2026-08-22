package com.jobseekercopilot.documentgenerationgateway.logging;

import org.slf4j.MDC;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;

@Configuration
public class CorrelationIdHttpClientConfig {

    @Bean
    RestTemplateCustomizer correlationIdRestTemplateCustomizer() {
        return restTemplate -> restTemplate.getInterceptors().add(correlationIdInterceptor());
    }

    @Bean
    RestClientCustomizer correlationIdRestClientCustomizer() {
        return restClientBuilder -> restClientBuilder.requestInterceptor(correlationIdInterceptor());
    }

    private ClientHttpRequestInterceptor correlationIdInterceptor() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (CorrelationIds.isValid(correlationId)) {
                request.getHeaders().set(CorrelationIdFilter.HEADER_NAME, correlationId);
            }
            return execution.execute(request, body);
        };
    }
}
