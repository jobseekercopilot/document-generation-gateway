package com.jobseekercopilot.documentgenerationgateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "document-generation.security.jwk-set-uri=http://127.0.0.1:65535/.well-known/jwks.json",
        "document-generation.security.authentication-service-token="
                + "test-only-authentication-service-token-32-bytes",
        "document-generation.security.application-tracker-producer-token="
                + "test-only-application-producer-token-32-bytes",
        "document-generation.security.cv-cover-letter-service-token="
                + "test-only-cv-cover-letter-service-token-32-bytes",
        "document-generation.security.document-export-service-token="
                + "test-only-document-export-service-token-32-bytes",
        "document-generation.security.document-store-producer-token="
                + "test-only-document-store-producer-token-32-bytes",
        "document-generation.security.document-store-reader-token="
                + "test-only-document-store-reader-token-32-bytes"
})
@AutoConfigureMockMvc
class OpenApiExportTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exportOpenApi() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode contract = objectMapper.readTree(spec);
        assertEquals("1.1.0", contract.path("info").path("version").asText());
        assertEquals(
                "bearer",
                contract.path("components")
                        .path("securitySchemes")
                        .path("bearerAuth")
                        .path("scheme")
                        .asText());
        assertFalse(spec.contains("X-User-Id"));
        assertEquals(
                java.util.Set.of("id", "title", "company", "description"),
                new java.util.HashSet<>(objectMapper.convertValue(
                        contract.path("components").path("schemas").path("Job").path("required"),
                        objectMapper.getTypeFactory().constructCollectionType(
                                java.util.List.class,
                                String.class))));
        contract.path("paths").forEach(path ->
                path.forEach(operation ->
                        assertTrue(operation.path("security").toString().contains("bearerAuth"))));
        Files.writeString(Path.of("target/openapi.json"), spec);
    }
}
