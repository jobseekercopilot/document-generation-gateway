package com.jobseekercopilot.documentgenerationgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Downloads grouped by generated document type")
public record GenerationDownloadsResponse(DocumentDownloadsResponse cv, DocumentDownloadsResponse coverLetter) {}
