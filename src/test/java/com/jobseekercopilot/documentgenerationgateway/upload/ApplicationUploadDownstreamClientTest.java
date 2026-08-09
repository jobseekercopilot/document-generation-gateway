package com.jobseekercopilot.documentgenerationgateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class ApplicationUploadDownstreamClientTest {
    private static final UUID APPLICATION_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OPERATION_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID DOCUMENT_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");

    private MockRestServiceServer server;
    private ApplicationUploadDownstreamClient client;
    private ApplicationUploadOperation operation;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new ApplicationUploadDownstreamClient(
                restTemplate,
                new ObjectMapper(),
                credentials(),
                "http://store",
                "http://tracker");
        operation = new ApplicationUploadOperation(
                OPERATION_ID,
                "owner-123",
                "browser-key",
                APPLICATION_ID,
                "canonical-job",
                DocumentKind.CV,
                UploadFormat.PDF,
                "a".repeat(64),
                "b".repeat(64),
                ApplicationUploadState.RECEIVED,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                Instant.parse("2026-08-09T00:00:00Z"),
                Instant.parse("2026-08-09T00:00:00Z"));
    }

    @Test
    void uploadUsesOnlyProducerIdentityBoundOwnerAndStableOperationKey() {
        server.expect(
                        once(),
                        requestTo("http://store/api/v1/applications/"
                                + APPLICATION_ID
                                + "/documents/CV/uploads?jobId=canonical-job&fileType=PDF"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", "e".repeat(32)))
                .andExpect(header("X-Document-Owner", "owner-123"))
                .andExpect(header("Idempotency-Key", OPERATION_ID + ":store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("clean-pdf")))
                .andRespond(withSuccess("{\"state\":\"READY\"}", MediaType.APPLICATION_JSON));

        assertThat(client.upload(
                        operation,
                        "clean-pdf".getBytes(StandardCharsets.UTF_8),
                        "cv.pdf"))
                .containsEntry("state", "READY");
        server.verify();
    }

    @Test
    void trackerLookupAndLinkUseOnlyServerDerivedOwnerContext() {
        server.expect(
                        once(),
                        requestTo("http://tracker/api/v1/applications/" + APPLICATION_ID))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", "b".repeat(32)))
                .andExpect(header("X-Application-Owner", "owner-123"))
                .andRespond(withSuccess(
                        "{\"id\":\"" + APPLICATION_ID + "\",\"version\":4}",
                        MediaType.APPLICATION_JSON));
        server.expect(
                        once(),
                        requestTo("http://tracker/api/v1/applications/"
                                + APPLICATION_ID
                                + "/document-selections"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Service-Token", "b".repeat(32)))
                .andExpect(header("X-Application-Owner", "owner-123"))
                .andExpect(header("Idempotency-Key", OPERATION_ID + ":link"))
                .andExpect(content().json("""
                        {
                          "cvSelection": {
                            "state": "SELECTED",
                            "documentId": "%s"
                          },
                          "coverLetterSelection": {"state": "OMITTED"},
                          "expectedVersion": 4
                        }
                        """.formatted(DOCUMENT_ID)))
                .andRespond(withSuccess(
                        "{\"id\":\"" + APPLICATION_ID + "\",\"version\":5}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.application("owner-123", APPLICATION_ID))
                .containsEntry("version", 4);
        assertThat(client.saveSelections(operation, 4, DOCUMENT_ID, null))
                .containsEntry("version", 5);
        server.verify();
    }

    private DownstreamServiceCredentials credentials() {
        return new DownstreamServiceCredentials(
                "a".repeat(32),
                "b".repeat(32),
                "c".repeat(32),
                "d".repeat(32),
                "e".repeat(32),
                "f".repeat(32),
                "g".repeat(32));
    }
}
