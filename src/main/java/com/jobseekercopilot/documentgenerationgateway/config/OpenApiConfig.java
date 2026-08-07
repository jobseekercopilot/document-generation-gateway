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
                        .version("2.2.0")
                        .description("Durably coordinates owner-scoped canonical snapshots, "
                                + "explicit purpose-bound evidence selections, bounded tailored "
                                + "document generation, approval, truthful document-family history "
                                + "and atomic application document selections."));
    }
}
