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
                + "test-only-document-store-reader-token-32-bytes",
        "document-generation.security.payment-service-token="
                + "test-only-payment-service-token-0000000000001"
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
        assertEquals("2.4.0", contract.path("info").path("version").asText());
        assertEquals(
                "bearer",
                contract.path("components")
                        .path("securitySchemes")
                        .path("bearerAuth")
                        .path("scheme")
                        .asText());
        assertFalse(spec.contains("X-User-Id"));
        assertFalse(contract.path("paths")
                .has("/api/v1/document-generation/jobs/{jobId}/generate"));
        assertTrue(contract.path("paths")
                .has("/api/v1/document-generation/document-families"));
        assertTrue(contract.path("paths")
                .has("/api/v1/document-generation/document-families/{documentFamilyId}"));
        assertTrue(contract.path("paths")
                .has("/api/v1/document-generation/document-families/{documentFamilyId}/current"));
        assertTrue(contract.path("paths")
                .has("/api/v1/document-generation/document-versions/{documentId}/application-associations"));
        assertTrue(contract.path("paths")
                .has("/api/v1/document-generation/document-versions/{documentId}/archive"));
        assertTrue(contract.path("paths")
                .has("/api/v1/document-generation/document-versions/{documentId}/restore"));
        assertTrue(contract.path("paths")
                .has("/api/v1/document-generation/document-versions/{documentId}"));
        JsonNode selections = contract.path("paths")
                .path("/api/v1/document-generation/applications/{applicationId}/document-selections")
                .path("put");
        assertTrue(selections.path("requestBody").path("required").asBoolean());
        assertTrue(selections.path("parameters").findValuesAsText("name")
                .contains("Idempotency-Key"));
        JsonNode start = contract.path("paths")
                .path("/api/v1/document-generation/saved-jobs/{savedJobId}/operations")
                .path("post");
        assertTrue(start.path("requestBody").path("required").asBoolean());
        assertEquals(
                "#/components/schemas/StartGenerationRequest",
                start.path("requestBody")
                        .path("content")
                        .path("application/json")
                        .path("schema")
                        .path("$ref")
                        .asText());
        JsonNode schemas = contract.path("components").path("schemas");
        assertTrue(schemas.path("DocumentVersionHistoryItem")
                .path("properties").has("purgedAt"));
        assertTrue(schemas.path("DocumentVersionHistoryItem")
                .path("properties").has("unavailableReason"));
        assertTrue(schemas.path("DocumentVersionHistoryItem")
                .path("properties").has("applicationAssociations"));
        assertFalse(schemas.path("DocumentApplicationAssociation")
                .path("properties").has("contentSha256"));
        JsonNode selectionResponse = schemas
                .path("ApplicationDocumentSelectionsResponse")
                .path("properties");
        assertTrue(selectionResponse.has("applicationUsedCvDocumentReference"));
        assertTrue(selectionResponse.has(
                "applicationUsedCoverLetterDocumentReference"));
        assertEquals(
                "[\"UNKNOWN\",\"SELECTED\",\"OMITTED\"]",
                selectionResponse.path("applicationUsedCvState")
                        .path("enum")
                        .toString());
        assertEquals(
                "[\"UNKNOWN\",\"SELECTED\",\"OMITTED\"]",
                selectionResponse.path("applicationUsedCoverLetterState")
                        .path("enum")
                        .toString());
        assertTrue(selectionResponse.has("applicationUsedAt"));
        assertTrue(selectionResponse.has("appliedAt"));
        assertEquals(
                java.util.Set.of(
                        "cvSelection",
                        "coverLetterSelection",
                        "expectedVersion"),
                new java.util.HashSet<>(objectMapper.convertValue(
                        schemas.path("SaveApplicationDocumentSelectionsRequest")
                                .path("required"),
                        objectMapper.getTypeFactory().constructCollectionType(
                                java.util.List.class,
                                String.class))));
        assertFalse(schemas.path("DocumentVersionHistoryItem")
                .path("properties").has("content"));
        assertFalse(schemas.path("DocumentArtifactManifestItem")
                .path("properties").has("fileName"));
        assertEquals(
                java.util.Set.of("documents"),
                new java.util.HashSet<>(objectMapper.convertValue(
                        schemas.path("StartGenerationRequest").path("required"),
                        objectMapper.getTypeFactory().constructCollectionType(
                                java.util.List.class,
                                String.class))));
        assertEquals(
                java.util.Set.of("purpose", "entryIds", "sectionOrder"),
                new java.util.HashSet<>(objectMapper.convertValue(
                        schemas.path("DocumentEvidenceSelection").path("required"),
                        objectMapper.getTypeFactory().constructCollectionType(
                                java.util.List.class,
                                String.class))));
        assertEquals(
                "[\"CV\",\"COVER_LETTER\"]",
                schemas.path("DocumentEvidenceSelection")
                        .path("properties")
                        .path("purpose")
                        .path("enum")
                        .toString());
        contract.path("paths").forEach(path ->
                path.forEach(operation ->
                        assertTrue(operation.path("security").toString().contains("bearerAuth"))));
        Files.writeString(Path.of("target/openapi.json"), spec);
    }
}
