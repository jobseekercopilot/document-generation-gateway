package com.jobseekercopilot.documentgenerationgateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI documentGenerationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Jobseeker Copilot - Document Generation Gateway API")
                .version("1.0.0")
                .description("Orchestrates profile lookup and tailored document generation."));
    }
}
