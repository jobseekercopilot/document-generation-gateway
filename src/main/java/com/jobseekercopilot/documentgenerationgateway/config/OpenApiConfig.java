package com.jobseekercopilot.documentgenerationgateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
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
                        .version("1.2.0")
                        .description("Orchestrates profile lookup and tailored document generation."));
    }

    @Bean
    OpenApiCustomizer documentGenerationSchemaRequirements() {
        return openApi -> openApi.getComponents()
                .getSchemas()
                .get("Job")
                .setRequired(List.of("id", "title", "company", "description"));
    }
}
