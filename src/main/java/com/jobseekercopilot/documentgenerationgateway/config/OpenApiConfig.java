package com.jobseekercopilot.documentgenerationgateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI documentGenerationOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        "bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .info(new Info()
                        .title("Jobseeker Copilot - Document Generation Gateway API")
                        .version("2.6.0")
                        .description("Durably coordinates owner-scoped canonical snapshots, "
                                + "explicit requested outputs and purpose-bound evidence selections, "
                                + "bounded independently usable tailored "
                                + "document generation, approval, truthful document-family history "
                                + "exact retained-artifact downloads and atomic application "
                                + "document selections with truthful frozen selection/omission "
                                + "state, plus free secure application-document upload and exact "
                                + "immutable-version linking."));
    }
}
