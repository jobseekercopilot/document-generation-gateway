package com.jobseekercopilot.documentgenerationgateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentGenerationResponse;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentFileDownloadService;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentGenerationService;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
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
class GatewayIdentityIntegrationTest {

    private static final TestJwksServer JWKS = new TestJwksServer();

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("document-generation.security.jwk-set-uri", JWKS::jwkSetUri);
        registry.add("document-generation.security.issuer", () -> TestJwksServer.ISSUER);
        registry.add("document-generation.security.audience", () -> TestJwksServer.AUDIENCE);
    }

    @AfterAll
    static void stopJwks() {
        JWKS.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentGenerationService generationService;

    @MockBean
    private DocumentFileDownloadService downloadService;

    @Test
    void browserIdentityHeaderCannotAuthenticateOrOverrideJwtSubject() throws Exception {
        mockMvc.perform(get("/api/v1/document-generation/files/{fileId}/download",
                        "00000000-0000-0000-0000-000000000001")
                        .header("X-User-Id", "victim"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        org.mockito.Mockito.when(generationService.generate(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any()))
                .thenReturn(new DocumentGenerationResponse(null, null, null, null));

        String token = JWKS.validToken("alice");
        mockMvc.perform(post("/api/v1/document-generation/jobs/{jobId}/generate", "job-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", "victim")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "job": {
                                    "id": "job-1",
                                    "title": "Developer",
                                    "company": "Example",
                                    "description": "Build things"
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        verify(generationService).generate(
                ArgumentMatchers.eq("alice"),
                ArgumentMatchers.eq("Bearer " + token),
                ArgumentMatchers.any());
    }

    @Test
    void validatedSubjectIsBoundToDocumentDownload() throws Exception {
        String token = JWKS.validToken("alice");
        String fileId = "00000000-0000-0000-0000-000000000001";
        org.mockito.Mockito.when(downloadService.download(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyString()))
                .thenReturn(ResponseEntity.ok(new byte[0]));

        mockMvc.perform(get("/api/v1/document-generation/files/{fileId}/download", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Document-Owner", "victim"))
                .andExpect(status().isOk());

        verify(downloadService).download(
                ArgumentMatchers.eq(java.util.UUID.fromString(fileId)),
                ArgumentMatchers.eq("alice"));
    }

    @Test
    void missingInvalidExpiredAndWrongPurposeTokensFailUniformly() throws Exception {
        List<String> invalidTokens = List.of(
                "not-a-jwt",
                JWKS.expiredToken("expired"),
                JWKS.forgedKnownKeyToken("forged"),
                JWKS.wrongIssuerToken("wrong-issuer"),
                JWKS.wrongAudienceToken("wrong-audience"),
                JWKS.refreshTokenType("wrong-type"),
                JWKS.missingSubjectToken());

        assertAuthenticationFailure(null);
        for (String token : invalidTokens) {
            assertAuthenticationFailure(token);
        }
    }

    private void assertAuthenticationFailure(String token) throws Exception {
        var request = get(
                "/api/v1/document-generation/files/{fileId}/download",
                "00000000-0000-0000-0000-000000000001");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Valid authentication is required."))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        if (token != null) {
            assertFalse(response.contains(token));
        }
        assertFalse(response.contains("127.0.0.1"));
        assertFalse(response.contains("subject"));
        assertFalse(response.contains("Jwt"));
    }
}
