package com.jobseekercopilot.documentgenerationgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "DOCX and PDF downloads for a generated document")
public record DocumentDownloadsResponse(DownloadFileResponse docx, DownloadFileResponse pdf) {}
