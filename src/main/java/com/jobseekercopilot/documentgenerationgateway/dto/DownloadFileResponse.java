package com.jobseekercopilot.documentgenerationgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Metadata needed by the frontend to download an exported file")
public record DownloadFileResponse(UUID fileId, String downloadUrl, String fileName) {}
