package com.jobseekercopilot.documentgenerationgateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobseekercopilot.documentgenerationgateway.dto.DocumentUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.GenerationOperationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.StartGenerationRequest;
import com.jobseekercopilot.documentgenerationgateway.generation.DurableGenerationService;
import com.jobseekercopilot.documentgenerationgateway.generation.GenerationOperationState;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentFileDownloadService;
import com.jobseekercopilot.documentgenerationgateway.service.DocumentGenerationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
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
                + "test-only-document-store-reader-token-32-bytes",
        "document-generation.security.payment-service-token="
                + "test-only-payment-service-token-0000000000001"
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

    @MockBean
    private DurableGenerationService durableGenerationService;

    @Test
    void browserIdentityHeaderCannotAuthenticate() throws Exception {
        mockMvc.perform(get("/api/v1/document-generation/files/{fileId}/download",
                        "00000000-0000-0000-0000-000000000001")
                        .header("X-User-Id", "victim"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

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
    void validatedSubjectAndExactRelationshipAreBoundToHistoricDownload()
            throws Exception {
        String token = JWKS.validToken("alice");
        UUID documentId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        org.mockito.Mockito.when(downloadService.downloadExactArtifact(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyString()))
                .thenReturn(ResponseEntity.ok(new byte[0]));

        mockMvc.perform(get(
                        "/api/v1/document-generation/documents/{documentId}/artifacts/{artifactId}/download",
                        documentId,
                        artifactId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Document-Owner", "victim"))
                .andExpect(status().isOk());

        verify(downloadService).downloadExactArtifact(
                ArgumentMatchers.eq(documentId),
                ArgumentMatchers.eq(artifactId),
                ArgumentMatchers.eq("alice"));
    }

    @Test
    void validatedSubjectAndBearerAreBoundToDurableGeneration()
            throws Exception {
        UUID savedJobId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String operationKey = "browser-generation-operation";
        String token = JWKS.validToken("alice");
        org.mockito.Mockito.when(durableGenerationService.start(
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.any(StartGenerationRequest.class)))
                .thenReturn(new GenerationOperationResponse(
                        operationId,
                        savedJobId,
                        GenerationOperationState.CREATED,
                        true,
                        false,
                        null,
                        null,
                        null,
                        java.util.Map.of(),
                        null,
                        null,
                        java.time.Instant.now().plusSeconds(600),
                        java.time.Instant.now(),
                        java.time.Instant.now()));

        mockMvc.perform(post(
                        "/api/v1/document-generation/saved-jobs/"
                                + "{savedJobId}/operations",
                        savedJobId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token)
                        .header("Idempotency-Key", operationKey)
                        .header("X-Document-Owner", "victim")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documents": [
                                    {
                                      "purpose": "CV",
                                      "entryIds": [
                                        "50000000-0000-4000-8000-000000000001"
                                      ],
                                      "sectionOrder": ["PROJECT"]
                                    },
                                    {
                                      "purpose": "COVER_LETTER",
                                      "entryIds": [
                                        "50000000-0000-4000-8000-000000000002"
                                      ],
                                      "sectionOrder": ["VOLUNTEERING"]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId")
                        .value(operationId.toString()));

        ArgumentCaptor<StartGenerationRequest> selection =
                ArgumentCaptor.forClass(StartGenerationRequest.class);
        verify(durableGenerationService).start(
                ArgumentMatchers.eq("alice"),
                ArgumentMatchers.eq("Bearer " + token),
                ArgumentMatchers.eq(savedJobId),
                ArgumentMatchers.eq(operationKey),
                selection.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of("CV", "COVER_LETTER"),
                selection.getValue().documents().stream()
                        .map(document -> document.purpose().name())
                        .toList());
    }

    @Test
    void recoverableReplacementReturnsAcceptedWithoutClaimingCompletion()
            throws Exception {
        UUID applicationId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String token = JWKS.validToken("alice");
        org.mockito.Mockito.when(generationService.replaceApplicationDocument(
                        ArgumentMatchers.eq(applicationId),
                        ArgumentMatchers.eq("alice"),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(new DocumentUploadResponse(
                        null,
                        applicationId,
                        "11111111-1111-4111-8111-111111111111",
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        operationId,
                        "RECOVERY_REQUIRED",
                        true,
                        "REPLACEMENT_STEP_FAILED",
                        "Replacement is pending recoverable completion."));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "cv.docx",
                "application/vnd.openxmlformats-officedocument"
                        + ".wordprocessingml.document",
                "synthetic".getBytes());

        mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.multipart(
                                "/api/v1/document-generation/applications/"
                                        + "{applicationId}/replace",
                                applicationId)
                        .file(file)
                        .param("documentType", "CV")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId")
                        .value(operationId.toString()))
                .andExpect(jsonPath("$.operationStatus")
                        .value("RECOVERY_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(true));
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
