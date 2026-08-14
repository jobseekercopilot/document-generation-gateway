package com.jobseekercopilot.documentgenerationgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentDownloadsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DownloadFileResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportFileItem;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportLatestFiles;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.generation.ExportIdempotencyKeys;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import com.jobseekercopilot.generated.documentexportservice.api.DocumentExportsApi;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportItem;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportRequest;
import com.jobseekercopilot.generated.documentexportservice.model.DocumentExportResponse;
import com.jobseekercopilot.generated.userprofileservice.api.UserProfilesApi;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentGenerationService {
    private static final Logger log = LoggerFactory.getLogger(DocumentGenerationService.class);
    private static final String DOWNLOAD_URL_TEMPLATE = "/api/v1/document-generation/files/%s/download";
    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String APPLICATION_OWNER_HEADER = "X-Application-Owner";
    private static final String DOCUMENT_OWNER_HEADER = "X-Document-Owner";

    private final UserProfilesApi userProfilesApi;
    private final DocumentExportsApi documentExportsApi;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String cvCoverLetterBaseUrl;
    private final String authenticationBaseUrl;
    private final String documentExportBaseUrl;
    private final String documentStoreBaseUrl;
    private final String applicationTrackerBaseUrl;
    private final String authenticationServiceToken;
    private final String applicationTrackerProducerToken;
    private final String cvCoverLetterServiceToken;
    private final String documentExportServiceToken;
    private final String documentStoreProducerToken;
    private final String documentStoreReaderToken;

    @Autowired
    public DocumentGenerationService(UserProfilesApi userProfilesApi,
                                     DocumentExportsApi documentExportsApi,
                                     ObjectMapper objectMapper,
                                     RestTemplate restTemplate,
                                     @Value("${services.cv-cover-letter-service.base-url}") String cvCoverLetterBaseUrl,
                                     @Value("${services.authentication-service.base-url}") String authenticationBaseUrl,
                                     @Value("${services.document-export-service.base-url}") String documentExportBaseUrl,
                                     @Value("${services.document-store-service.base-url}") String documentStoreBaseUrl,
                                     @Value("${services.application-tracker-service.base-url}") String applicationTrackerBaseUrl,
                                     DownstreamServiceCredentials credentials) {
        this.userProfilesApi = userProfilesApi;
        this.documentExportsApi = documentExportsApi;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.cvCoverLetterBaseUrl = cvCoverLetterBaseUrl;
        this.authenticationBaseUrl = authenticationBaseUrl;
        this.documentExportBaseUrl = documentExportBaseUrl;
        this.documentStoreBaseUrl = documentStoreBaseUrl;
        this.applicationTrackerBaseUrl = applicationTrackerBaseUrl;
        this.authenticationServiceToken = credentials.authenticationServiceToken();
        this.applicationTrackerProducerToken = credentials.applicationTrackerProducerToken();
        this.cvCoverLetterServiceToken = credentials.cvCoverLetterServiceToken();
        this.documentExportServiceToken = credentials.documentExportServiceToken();
        this.documentStoreProducerToken = credentials.documentStoreProducerToken();
        this.documentStoreReaderToken = credentials.documentStoreReaderToken();
    }

    DocumentGenerationService(UserProfilesApi userProfilesApi,
                              DocumentExportsApi documentExportsApi,
                              ObjectMapper objectMapper,
                              RestTemplate restTemplate,
                              String cvCoverLetterBaseUrl,
                              String authenticationBaseUrl,
                              String documentExportBaseUrl,
                              String documentStoreBaseUrl,
                              String applicationTrackerBaseUrl,
                              String authenticationServiceToken,
                              String applicationTrackerProducerToken,
                              String cvCoverLetterServiceToken,
                              String documentExportServiceToken,
                              String documentStoreProducerToken,
                              String documentStoreReaderToken,
                              String paymentServiceToken) {
        this(userProfilesApi,
                documentExportsApi,
                objectMapper,
                restTemplate,
                cvCoverLetterBaseUrl,
                authenticationBaseUrl,
                documentExportBaseUrl,
                documentStoreBaseUrl,
                applicationTrackerBaseUrl,
                new DownstreamServiceCredentials(
                        authenticationServiceToken,
                        applicationTrackerProducerToken,
                        cvCoverLetterServiceToken,
                        documentExportServiceToken,
                        documentStoreProducerToken,
                        documentStoreReaderToken,
                        paymentServiceToken));
    }

    public DocumentUploadResponse uploadReplacement(UUID generatedDocumentId, String userId, MultipartFile file,
                                                    DocumentKind documentKind, UploadFormat uploadedFormat) {
        return uploadReplacementFile(
                generatedDocumentId,
                userId,
                file,
                documentKind,
                uploadedFormat,
                true,
                null);
    }

    private DocumentUploadResponse uploadReplacementFile(UUID generatedDocumentId, String userId, MultipartFile file,
                                                         DocumentKind documentKind, UploadFormat uploadedFormat,
                                                         boolean validateApplicationLock,
                                                         String idempotencyKey) {
        long startedAt = System.nanoTime();
        log.info("Replacement document upload received generatedDocumentId={} documentKind={} uploadedFormat={} sizeBytes={}",
                generatedDocumentId,
                documentKind,
                uploadedFormat,
                file == null ? 0 : file.getSize());
        validateUpload(file, documentKind, uploadedFormat);
        if (validateApplicationLock) {
            validateApplicationAllowsDocumentReplacement(generatedDocumentId, userId);
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(mimeType(uploadedFormat)));
            body.add("file", new HttpEntity<>(resource, fileHeaders));
            body.add("documentKind", documentKind.name());
            body.add("uploadedFormat", uploadedFormat.name());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set(SERVICE_TOKEN_HEADER, documentExportServiceToken);
            headers.set(DOCUMENT_OWNER_HEADER, requireDocumentOwner(userId));
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                headers.set("Idempotency-Key", idempotencyKey);
            }

            ExportUploadResponse response = restTemplate.postForObject(
                    documentExportBaseUrl + "/api/v1/document-exports/documents/{generatedDocumentId}/upload",
                    new HttpEntity<>(body, headers),
                    ExportUploadResponse.class,
                    generatedDocumentId);
            if (response == null) {
                throw new IllegalStateException("Document export service returned no upload result");
            }
            log.info("Replacement document upload completed generatedDocumentId={} documentKind={} durationMs={}",
                    generatedDocumentId,
                    documentKind,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return toGatewayUploadResponse(response);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Unable to read uploaded file");
        }
    }

    public DocumentUploadResponse replaceApplicationDocument(UUID applicationId, String userId,
                                                             MultipartFile file, DocumentKind documentKind) {
        long startedAt = System.nanoTime();
        validateDocxUpload(file, documentKind);
        Map<?, ?> application = fetchApplication(applicationId, userId);
        validateApplicationOwner(application, userId);
        validateApplicationStatus(application);
        Map<?, ?> workflow = beginReplacement(
                applicationId,
                userId,
                documentKind,
                sha256(file));
        UUID operationId = requiredUuid(workflow, "operationId");
        UUID sourceDocumentId = requiredUuid(workflow, "sourceDocumentId");
        UUID newDocumentId = optionalUuid(workflow, "replacementDocumentId");
        Integer version = null;
        DocumentUploadResponse uploadResponse = null;
        try {
            Map<?, ?> currentDocument = restTemplate.exchange(
                    documentStoreBaseUrl + "/api/v1/documents/{documentId}",
                    HttpMethod.GET,
                    documentStoreRequest(
                            userId, null, documentStoreReaderToken),
                    Map.class,
                    sourceDocumentId).getBody();
            if (currentDocument == null
                    || currentDocument.get("documentFamilyId") == null) {
                throw new IllegalArgumentException(
                        "Source document family is missing");
            }
            String content = extractDocxText(file);
            String title = firstText(
                    stringValue(currentDocument, "title"),
                    ("%s replacement for %s").formatted(
                            documentKind == DocumentKind.CV
                                    ? "CV"
                                    : "Cover letter",
                            stringValue(application, "jobTitle")));

            Map<String, Object> createDocument = new LinkedHashMap<>();
            createDocument.put("userId", userId);
            createDocument.put(
                    "jobId", stringValue(application, "jobId"));
            createDocument.put(
                    "applicationId", applicationId.toString());
            createDocument.put(
                    "documentFamilyId",
                    currentDocument.get("documentFamilyId"));
            createDocument.put("documentType", documentKind.name());
            createDocument.put("title", title);
            createDocument.put("content", content);
            createDocument.put("active", false);
            createDocument.put(
                    "originalFilename", file.getOriginalFilename());
            createDocument.put("sourceType", "UPLOADED");
            createDocument.put("createdBy", userId);

            Map<?, ?> created = restTemplate.exchange(
                    documentStoreBaseUrl + "/api/v1/documents",
                    HttpMethod.POST,
                    documentStoreRequest(
                            userId,
                            createDocument,
                            documentStoreProducerToken,
                            operationId + ":document"),
                    Map.class).getBody();
            if (created == null || created.get("id") == null) {
                throw new IllegalStateException(
                        "Document Store returned no replacement document");
            }
            newDocumentId = UUID.fromString(
                    Objects.toString(created.get("id")));
            version = integerValue(created.get("version"));
            registerReplacement(
                    applicationId, userId, operationId, newDocumentId);

            uploadResponse = uploadReplacementFile(
                    newDocumentId,
                    userId,
                    file,
                    documentKind,
                    UploadFormat.DOCX,
                    false,
                    operationId.toString());

            restTemplate.exchange(
                    documentStoreBaseUrl
                            + "/api/v1/documents/{documentId}/approve",
                    HttpMethod.PATCH,
                    documentStoreRequest(
                            userId, null, documentStoreProducerToken),
                    Map.class,
                    newDocumentId);

            Map<?, ?> completed = completeReplacement(
                    applicationId, userId, operationId);
            log.info("Application document replacement completed applicationId={} documentKind={} version={} durationMs={}",
                    applicationId,
                    documentKind,
                    version,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return replacementResponse(
                    completed,
                    newDocumentId,
                    applicationId,
                    version,
                    uploadResponse,
                    documentKind);
        } catch (RuntimeException exception) {
            Map<?, ?> recovery = markReplacementRecovery(
                    applicationId, userId, operationId);
            log.warn(
                    "Application document replacement requires recovery applicationId={} documentKind={} error={}",
                    applicationId,
                    documentKind,
                    exception.getClass().getSimpleName());
            return replacementResponse(
                    recovery,
                    newDocumentId,
                    applicationId,
                    version,
                    uploadResponse,
                    documentKind);
        }
    }

    DocumentDownloadsResponse exportDocument(UUID documentId, String userId) {
        long startedAt = System.nanoTime();
        log.info("Calling document-export-service documentId={}", documentId);
        DocumentExportResponse response = documentExportsApi.exportDocument(
                requireDocumentOwner(userId),
                documentId,
                ExportIdempotencyKeys.forDocument(documentId),
                new DocumentExportRequest()
                        .formats(List.of(
                                DocumentExportRequest.FormatsEnum.DOCX,
                                DocumentExportRequest.FormatsEnum.PDF)));
        if (response == null || response.getExports() == null) {
            throw new IllegalStateException("Document export service returned no export results");
        }

        DownloadFileResponse docx = response.getExports().stream()
                .filter(item -> item.getFormat() == DocumentExportItem.FormatEnum.DOCX)
                .findFirst()
                .map(this::toDownload)
                .orElseThrow(() -> new IllegalStateException("Document export service returned no DOCX export"));
        DownloadFileResponse pdf = response.getExports().stream()
                .filter(item -> item.getFormat() == DocumentExportItem.FormatEnum.PDF)
                .findFirst()
                .map(this::toDownload)
                .orElseThrow(() -> new IllegalStateException("Document export service returned no PDF export"));
        log.info("document-export-service returned documentId={} exports={} durationMs={}",
                documentId,
                response.getExports().size(),
                (System.nanoTime() - startedAt) / 1_000_000);

        return new DocumentDownloadsResponse(docx, pdf);
    }

    private DownloadFileResponse toDownload(DocumentExportItem item) {
        UUID fileId = Objects.requireNonNull(item.getFileId(), "Exported file id is required");
        return new DownloadFileResponse(
                fileId,
                DOWNLOAD_URL_TEMPLATE.formatted(fileId),
                item.getFileName());
    }

    private DocumentUploadResponse toGatewayUploadResponse(ExportUploadResponse response) {
        ExportLatestFiles latest = response.latestFiles();
        return new DocumentUploadResponse(
                response.generatedDocumentId(),
                null,
                null,
                null,
                null,
                toDownload(response.uploadedFile()),
                response.regeneratedFiles() == null
                        ? List.of()
                        : response.regeneratedFiles().stream().map(this::toDownload).toList(),
                new DocumentDownloadsResponse(
                        latest == null ? null : toDownload(latest.docx()),
                        latest == null ? null : toDownload(latest.pdf())),
                null,
                null,
                false,
                null,
                response.message());
    }

    private Map<?, ?> beginReplacement(
            UUID applicationId,
            String userId,
            DocumentKind documentKind,
            String requestSha256) {
        Map<String, Object> request = Map.of(
                "documentType", documentKind.name(),
                "requestSha256", requestSha256);
        Map<?, ?> response = restTemplate.exchange(
                applicationTrackerBaseUrl
                        + "/api/v1/applications/{applicationId}/document-replacements",
                HttpMethod.POST,
                applicationTrackerRequest(userId, request),
                Map.class,
                applicationId).getBody();
        if (response == null) {
            throw new IllegalStateException(
                    "Application Tracker returned no replacement workflow");
        }
        return response;
    }

    private void registerReplacement(
            UUID applicationId,
            String userId,
            UUID operationId,
            UUID replacementDocumentId) {
        restTemplate.exchange(
                applicationTrackerBaseUrl
                        + "/api/v1/applications/{applicationId}/document-replacements/{operationId}/replacement-document",
                HttpMethod.PATCH,
                applicationTrackerRequest(
                        userId,
                        Map.of(
                                "replacementDocumentId",
                                replacementDocumentId)),
                Map.class,
                applicationId,
                operationId);
    }

    private Map<?, ?> completeReplacement(
            UUID applicationId,
            String userId,
            UUID operationId) {
        Map<?, ?> response = restTemplate.exchange(
                applicationTrackerBaseUrl
                        + "/api/v1/applications/{applicationId}/document-replacements/{operationId}/complete",
                HttpMethod.PATCH,
                applicationTrackerRequest(userId, null),
                Map.class,
                applicationId,
                operationId).getBody();
        if (response == null) {
            throw new IllegalStateException(
                    "Application Tracker returned no replacement outcome");
        }
        return response;
    }

    private Map<?, ?> markReplacementRecovery(
            UUID applicationId,
            String userId,
            UUID operationId) {
        Map<?, ?> response = restTemplate.exchange(
                applicationTrackerBaseUrl
                        + "/api/v1/applications/{applicationId}/document-replacements/{operationId}/recovery-required",
                HttpMethod.PATCH,
                applicationTrackerRequest(userId, null),
                Map.class,
                applicationId,
                operationId).getBody();
        if (response == null) {
            throw new IllegalStateException(
                    "Application Tracker returned no recovery outcome");
        }
        return response;
    }

    private DocumentUploadResponse replacementResponse(
            Map<?, ?> workflow,
            UUID replacementDocumentId,
            UUID applicationId,
            Integer version,
            DocumentUploadResponse upload,
            DocumentKind documentKind) {
        String operationStatus =
                stringValue(workflow, "operationStatus");
        boolean completed = "COMPLETED".equals(operationStatus);
        UUID resolvedDocumentId = replacementDocumentId == null
                ? optionalUuid(workflow, "replacementDocumentId")
                : replacementDocumentId;
        return new DocumentUploadResponse(
                resolvedDocumentId,
                applicationId,
                stringValue(workflow, "cvDocumentId"),
                stringValue(workflow, "coverLetterDocumentId"),
                version,
                upload == null ? null : upload.uploadedFile(),
                upload == null ? List.of() : upload.regeneratedFiles(),
                upload == null ? null : upload.latestFiles(),
                requiredUuid(workflow, "operationId"),
                operationStatus,
                booleanValue(workflow, "retryable"),
                stringValue(workflow, "recoveryCode"),
                completed
                        ? documentKind == DocumentKind.CV
                                ? "CV replaced successfully. PDF version has been updated."
                                : "Cover letter replaced successfully. PDF version has been updated."
                        : "Document replacement is pending recoverable completion.");
    }

    private UUID requiredUuid(Map<?, ?> map, String key) {
        UUID value = optionalUuid(map, key);
        if (value == null) {
            throw new IllegalStateException(key + " is missing");
        }
        return value;
    }

    private UUID optionalUuid(Map<?, ?> map, String key) {
        String value = stringValue(map, key);
        return value == null || value.isBlank()
                ? null
                : UUID.fromString(value);
    }

    private boolean booleanValue(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof Boolean bool
                ? bool
                : Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private String sha256(MultipartFile file) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(file.getBytes()));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException(
                    "Unable to read uploaded file");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    private DownloadFileResponse toDownload(ExportFileItem item) {
        if (item == null) {
            return null;
        }
        UUID fileId = Objects.requireNonNull(item.fileId(), "Exported file id is required");
        return new DownloadFileResponse(
                fileId,
                DOWNLOAD_URL_TEMPLATE.formatted(fileId),
                item.fileName());
    }

    private UUID parseDocumentId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Generated " + label + " document id is missing");
        }
        return UUID.fromString(value);
    }

    private void validateUpload(MultipartFile file, DocumentKind documentKind, UploadFormat uploadedFormat) {
        if (documentKind == null) {
            throw new IllegalArgumentException("documentKind is required");
        }
        if (uploadedFormat == null) {
            throw new IllegalArgumentException("uploadedFormat is required");
        }
        if (uploadedFormat != UploadFormat.DOCX) {
            throw new IllegalArgumentException("Only .docx files can be uploaded.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is required");
        }
    }

    private void validateDocxUpload(MultipartFile file, DocumentKind documentKind) {
        if (documentKind == null) {
            throw new IllegalArgumentException("documentType is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is required");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String contentType = file.getContentType();
        if (!originalName.endsWith(".docx")
                || (contentType != null
                && !contentType.isBlank()
                && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(contentType)
                && !"application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(contentType))
                || !hasDocxStructure(file)) {
            throw new IllegalArgumentException("Only .docx files can be uploaded.");
        }
    }

    private boolean hasDocxStructure(MultipartFile file) {
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            boolean hasContentTypes = false;
            boolean hasDocumentXml = false;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    hasContentTypes = true;
                }
                if ("word/document.xml".equals(entry.getName())) {
                    hasDocumentXml = true;
                }
                if (hasContentTypes && hasDocumentXml) {
                    return true;
                }
            }
            return false;
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    private String extractDocxText(MultipartFile file) {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            if (text == null || text.isBlank()) {
                return "Uploaded document";
            }
            return text.trim();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Only .docx files can be uploaded.");
        }
    }

    private Map<?, ?> fetchApplication(UUID applicationId, String userId) {
        Map<?, ?> application = restTemplate.exchange(
                applicationTrackerBaseUrl + "/api/v1/applications/{applicationId}",
                HttpMethod.GET,
                applicationTrackerRequest(userId, null),
                Map.class,
                applicationId).getBody();
        if (application == null) {
            throw new IllegalArgumentException("Application not found");
        }
        return application;
    }

    private void validateApplicationOwner(Map<?, ?> application, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated application owner is required.");
        }
        String owner = stringValue(application, "userId");
        if (owner == null || !owner.equals(userId)) {
            throw new IllegalArgumentException("Application does not belong to the current user.");
        }
    }

    private void validateApplicationStatus(Map<?, ?> application) {
        if (!"DOCUMENTS_GENERATED".equals(stringValue(application, "status"))) {
            throw new IllegalArgumentException("Documents cannot be replaced after the application has been marked as applied.");
        }
    }

    private String currentDocumentId(Map<?, ?> application, DocumentKind documentKind) {
        return documentKind == DocumentKind.CV
                ? stringValue(application, "cvDocumentId")
                : stringValue(application, "coverLetterDocumentId");
    }

    private String stringValue(Map<?, ?> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : value.toString();
    }

    private String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        return Integer.valueOf(value.toString());
    }

    private void validateApplicationAllowsDocumentReplacement(UUID generatedDocumentId, String userId) {
        long startedAt = System.nanoTime();
        log.info("Calling application-tracker-service before document replacement documentId={}", generatedDocumentId);
        Map<?, ?> application = restTemplate.exchange(
                applicationTrackerBaseUrl + "/api/v1/applications/document/{documentId}",
                HttpMethod.GET,
                applicationTrackerRequest(userId, null),
                Map.class,
                generatedDocumentId.toString()).getBody();
        Object status = application == null ? null : application.get("status");
        if (!"DOCUMENTS_GENERATED".equals(status)) {
            log.warn("Locked document upload rejected documentId={} status={}", generatedDocumentId, status);
            throw new IllegalArgumentException("Documents cannot be replaced after the application has been marked as applied.");
        }
        log.info("application-tracker-service replacement check passed documentId={} durationMs={}",
                generatedDocumentId,
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    private HttpEntity<?> applicationTrackerRequest(String userId, Object body) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated application owner is required.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, applicationTrackerProducerToken);
        headers.set(APPLICATION_OWNER_HEADER, userId);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<?> documentStoreRequest(
            String userId,
            Object body,
            String serviceToken) {
        return documentStoreRequest(
                userId, body, serviceToken, null);
    }

    private HttpEntity<?> documentStoreRequest(
            String userId,
            Object body,
            String serviceToken,
            String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, serviceToken);
        headers.set(DOCUMENT_OWNER_HEADER, requireDocumentOwner(userId));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }

    private String requireDocumentOwner(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated document owner is required.");
        }
        return userId;
    }

    private String mimeType(UploadFormat uploadedFormat) {
        return switch (uploadedFormat) {
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case PDF -> MediaType.APPLICATION_PDF_VALUE;
        };
    }
}
