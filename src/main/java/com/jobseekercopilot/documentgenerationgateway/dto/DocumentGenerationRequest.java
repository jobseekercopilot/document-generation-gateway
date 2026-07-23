package com.jobseekercopilot.documentgenerationgateway.dto;

import com.jobseekercopilot.generated.cvcoverletterservice.model.Job;
import jakarta.validation.constraints.NotNull;

public class DocumentGenerationRequest {
    @NotNull
    private Job job;

    public Job getJob() { return job; }
    public void setJob(Job job) { this.job = job; }
}
