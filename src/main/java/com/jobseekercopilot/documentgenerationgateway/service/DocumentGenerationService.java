package com.jobseekercopilot.documentgenerationgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentKind;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentDownloadsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentGenerationResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DocumentUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.DownloadFileResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportFileItem;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportLatestFiles;
import com.jobseekercopilot.documentgenerationgateway.dto.ExportUploadResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.GenerationDownloadsResponse;
import com.jobseekercopilot.documentgenerationgateway.dto.UploadFormat;
import com.jobseekercopilot.documentgenerationgateway.security.DownstreamServiceCredentials;
import com.jobseekercopilot.generated.cvcoverletterservice.model.GenerateCvCoverLetterResponse;
import com.jobseekercopilot.generated.cvcoverletterservice.model.Job;
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
                              String documentStoreReaderToken) {
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
                        documentStoreReaderToken));
    }

    public DocumentGenerationResponse generate(String userId, String authorization, Job job) {
        long startedAt = System.nanoTime();
        log.info("Document generation request received userId={} jobId={}",
                userId,
                job == null ? null : job.getId());
        long profileStartedAt = System.nanoTime();
        log.info("Calling user-profile-service for document generation userId={}", userId);
        var downstreamProfile = userProfilesApi.getMyProfile();
        if (downstreamProfile == null) {
            throw new IllegalStateException("User profile service returned no profile");
        }
        log.info("user-profile-service returned profile userId={} durationMs={}",
                userId,
                (System.nanoTime() - profileStartedAt) / 1_000_000);

        Map<String, Object> profile = objectMapper.convertValue(downstreamProfile, LinkedHashMap.class);
        enrichContactDetails(profile, authorization);
        GenerateCvCoverLetterResponse generated = generateCvAndCoverLetter(userId, profile, job);
        if (generated == null) {
            throw new IllegalStateException("CV cover letter service returned no generation result");
        }

        UUID cvDocumentId = parseDocumentId(generated.getCvDocumentId(), "CV");
        UUID coverLetterDocumentId = parseDocumentId(generated.getCoverLetterDocumentId(), "cover letter");

        DocumentDownloadsResponse cvDownloads = exportDocument(cvDocumentId, userId);
        DocumentDownloadsResponse coverLetterDownloads =
                exportDocument(coverLetterDocumentId, userId);
        log.info("Document generation gateway completed userId={} applicationId={} cvDocumentId={} coverLetterDocumentId={} durationMs={}",
                userId,
                generated.getApplicationId(),
                generated.getCvDocumentId(),
                generated.getCoverLetterDocumentId(),
                (System.nanoTime() - startedAt) / 1_000_000);

        return new DocumentGenerationResponse(
                generated.getApplicationId(),
                generated.getCvDocumentId(),
                generated.getCoverLetterDocumentId(),
                new GenerationDownloadsResponse(cvDownloads, coverLetterDownloads));
    }

    public DocumentUploadResponse uploadReplacement(UUID generatedDocumentId, String userId, MultipartFile file,
                                                    DocumentKind documentKind, UploadFormat uploadedFormat) {
        return uploadReplacementFile(generatedDocumentId, userId, file, documentKind, uploadedFormat, true);
    }

    private DocumentUploadResponse uploadReplacementFile(UUID generatedDocumentId, String userId, MultipartFile file,
                                                         DocumentKind documentKind, UploadFormat uploadedFormat,
                                                         boolean validateApplicationLock) {
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

        String currentDocumentId = currentDocumentId(application, documentKind);
        if (currentDocumentId == null || currentDocumentId.isBlank()) {
            throw new IllegalArgumentException("Active document reference is missing for " + documentKind);
        }

        Map<?, ?> currentDocument = restTemplate.exchange(
                documentStoreBaseUrl + "/api/v1/documents/{documentId}",
                HttpMethod.GET,
                documentStoreRequest(userId, null, documentStoreReaderToken),
                Map.class,
                currentDocumentId).getBody();
        if (currentDocument == null) {
            throw new IllegalArgumentException("Document not found");
        }
        String content = extractDocxText(file);
        String title = firstText(
                stringValue(currentDocument, "title"),
                ("%s replacement for %s").formatted(documentKind == DocumentKind.CV ? "CV" : "Cover letter",
                        stringValue(application, "jobTitle")));

        Map<String, Object> createDocument = new LinkedHashMap<>();
        createDocument.put("userId", userId);
        createDocument.put("jobId", stringValue(application, "jobId"));
        createDocument.put("applicationId", applicationId.toString());
        createDocument.put("documentType", documentKind.name());
        createDocument.put("title", title);
        createDocument.put("content", content);
        createDocument.put("active", false);
        createDocument.put("originalFilename", file.getOriginalFilename());
        createDocument.put("sourceType", "UPLOADED");
        createDocument.put("createdBy", userId);

        Map<?, ?> created = restTemplate.exchange(
                documentStoreBaseUrl + "/api/v1/documents",
                HttpMethod.POST,
                documentStoreRequest(userId, createDocument, documentStoreProducerToken),
                Map.class).getBody();
        if (created == null || created.get("id") == null) {
            throw new IllegalStateException("Document Store returned no replacement document");
        }
        UUID newDocumentId = UUID.fromString(Objects.toString(created.get("id")));
        Integer version = integerValue(created.get("version"));

        DocumentUploadResponse uploadResponse;
        try {
            uploadResponse = uploadReplacementFile(
                    newDocumentId,
                    userId,
                    file,
                    documentKind,
                    UploadFormat.DOCX,
                    false);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("DOCUMENT_CONVERSION_FAILED", exception);
        }

        restTemplate.exchange(
                documentStoreBaseUrl + "/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}",
                HttpMethod.PATCH,
                documentStoreRequest(userId, null, documentStoreProducerToken),
                Map.class,
                applicationId.toString(),
                documentKind.name(),
                newDocumentId);

        Map<String, Object> referenceUpdate = Map.of(
                "documentType", documentKind.name(),
                "documentId", newDocumentId.toString());
        ResponseEntity<Map> updatedApplicationResponse = restTemplate.exchange(
                applicationTrackerBaseUrl + "/api/v1/applications/{applicationId}/document-reference",
                HttpMethod.PATCH,
                applicationTrackerRequest(userId, referenceUpdate),
                Map.class,
                applicationId);
        Map<?, ?> updatedApplication = updatedApplicationResponse.getBody();

        log.info("Application document replacement completed applicationId={} documentKind={} newDocumentId={} version={} durationMs={}",
                applicationId,
                documentKind,
                newDocumentId,
                version,
                (System.nanoTime() - startedAt) / 1_000_000);
        return new DocumentUploadResponse(
                newDocumentId,
                applicationId,
                stringValue(updatedApplication, "cvDocumentId"),
                stringValue(updatedApplication, "coverLetterDocumentId"),
                version,
                uploadResponse.uploadedFile(),
                uploadResponse.regeneratedFiles(),
                uploadResponse.latestFiles(),
                documentKind == DocumentKind.CV
                        ? "CV replaced successfully. PDF version has been updated."
                        : "Cover letter replaced successfully. PDF version has been updated.");
    }

    private GenerateCvCoverLetterResponse generateCvAndCoverLetter(String userId, Map<String, Object> profile, Job job) {
        long startedAt = System.nanoTime();
        log.info("Calling cv-cover-letter-service userId={} jobId={}", userId, job == null ? null : job.getId());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userProfile", profile);
        request.put("job", job);
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, cvCoverLetterServiceToken);
        headers.set(DOCUMENT_OWNER_HEADER, requireDocumentOwner(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        GenerateCvCoverLetterResponse response = restTemplate.postForObject(
                cvCoverLetterBaseUrl + "/api/v1/cv-cover-letter/generate",
                new HttpEntity<>(request, headers),
                GenerateCvCoverLetterResponse.class);
        log.info("cv-cover-letter-service returned userId={} jobId={} applicationId={} durationMs={}",
                userId,
                job == null ? null : job.getId(),
                response == null ? null : response.getApplicationId(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return response;
    }

    private void enrichContactDetails(Map<String, Object> profile, String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return;
        }
        try {
            long startedAt = System.nanoTime();
            log.info("Calling authentication-service for contact enrichment");
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
            headers.set(SERVICE_TOKEN_HEADER, authenticationServiceToken);
            Map<?, ?> account = restTemplate.exchange(
                    authenticationBaseUrl + "/api/auth/me",
                    org.springframework.http.HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class).getBody();
            if (account != null) {
                putIfText(profile, "fullName", account.get("name"));
                putIfText(profile, "email", account.get("email"));
            }
            log.info("authentication-service contact enrichment completed durationMs={}",
                    (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RestClientException exception) {
            log.warn("authentication-service contact enrichment failed error={}",
                    exception.getClass().getSimpleName());
            // Contact details improve document presentation, but generation should not fail if auth lookup is unavailable.
        }
    }

    private void putIfText(Map<String, Object> profile, String key, Object value) {
        if (value instanceof String text && !text.isBlank()) {
            profile.put(key, text.trim());
        }
    }

    private DocumentDownloadsResponse exportDocument(UUID documentId, String userId) {
        long startedAt = System.nanoTime();
        log.info("Calling document-export-service documentId={}", documentId);
        DocumentExportResponse response = documentExportsApi.exportDocument(
                requireDocumentOwner(userId),
                documentId,
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
                response.message());
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
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, serviceToken);
        headers.set(DOCUMENT_OWNER_HEADER, requireDocumentOwner(userId));
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
