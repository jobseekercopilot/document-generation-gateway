package com.jobseekercopilot.documentgenerationgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionSlotRequest;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionState;
import com.jobseekercopilot.documentgenerationgateway.dto.ApplicationDocumentSelectionsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.SaveApplicationDocumentSelectionsRequest;
import com.jobseekercopilot.documentgenerationgateway.exception.ApplicationSelectionDownstreamException;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Service
public class ApplicationDocumentSelectionService {
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String OWNER_HEADER = "X-Application-Owner";
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String trackerBaseUrl;
    private final String trackerProducerToken;

    @Autowired
    public ApplicationDocumentSelectionService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${services.application-tracker-service.base-url}")
            String trackerBaseUrl,
            DownstreamServiceCredentials credentials) {
        this(
                restTemplate,
                objectMapper,
                trackerBaseUrl,
                credentials.applicationTrackerProducerToken());
    }

    ApplicationDocumentSelectionService(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            String trackerBaseUrl,
            String trackerProducerToken) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.trackerBaseUrl = trackerBaseUrl;
        this.trackerProducerToken = trackerProducerToken;
    }

    public ApplicationDocumentSelectionsResponse save(
            String ownerId,
            UUID applicationId,
            String idempotencyKey,
            SaveApplicationDocumentSelectionsRequest request) {
        requireOwner(ownerId);
        requireIdempotencyKey(idempotencyKey);
        validateSlot("cvSelection", request.cvSelection());
        validateSlot("coverLetterSelection", request.coverLetterSelection());
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, trackerProducerToken);
        headers.set(OWNER_HEADER, ownerId);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ApplicationDocumentSelectionsResponse response = restTemplate.exchange(
                    trackerBaseUrl
                            + "/api/v1/applications/{applicationId}/document-selections",
                    HttpMethod.PUT,
                    new HttpEntity<>(request, headers),
                    ApplicationDocumentSelectionsResponse.class,
                    applicationId).getBody();
            if (response == null) {
                throw new IllegalStateException(
                        "Application Tracker returned an empty selection response.");
            }
            return response;
        } catch (HttpStatusCodeException exception) {
            throw new ApplicationSelectionDownstreamException(
                    exception.getStatusCode(),
                    currentApplication(exception));
        }
    }

    private ApplicationDocumentSelectionsResponse currentApplication(
            HttpStatusCodeException exception) {
        if (exception.getStatusCode().value() != 409
                || exception.getResponseBodyAsByteArray().length == 0) {
            return null;
        }
        try {
            JsonNode current = objectMapper
                    .readTree(exception.getResponseBodyAsByteArray())
                    .path("currentApplication");
            return current.isObject()
                    ? objectMapper.treeToValue(
                            current,
                            ApplicationDocumentSelectionsResponse.class)
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated application owner is required.");
        }
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1-128 safe characters.");
        }
    }

    private void validateSlot(
            String name, ApplicationDocumentSelectionSlotRequest slot) {
        if (slot == null || slot.state() == null) {
            throw new IllegalArgumentException(name + " is required.");
        }
        boolean selected =
                slot.state() == ApplicationDocumentSelectionState.SELECTED;
        if (selected != (slot.documentId() != null)) {
            throw new IllegalArgumentException(
                    name + " documentId must be present only for SELECTED.");
        }
    }
}
