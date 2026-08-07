#!/usr/bin/env bash
set -euo pipefail

contract_dir="${1:-src/main/openapi}"
manifest="$contract_dir/SHA256SUMS"
contract_names=(
    application-tracker-service
    authentication-service
    cv-cover-letter-service
    document-export-service
    document-store-service
    payment-service
    user-profile-service
)

for contract_name in "${contract_names[@]}"; do
    for required_file in \
        "$contract_dir/$contract_name.json" \
        "$contract_dir/$contract_name.SOURCE"; do
        if [[ ! -f "$required_file" || -L "$required_file" ]]; then
            echo "contract policy: required regular file is missing or is a symlink: $required_file" >&2
            exit 1
        fi
    done
done

for required_file in \
    "$contract_dir/job-service.yaml" \
    "$contract_dir/job-service.SOURCE"; do
    if [[ ! -f "$required_file" || -L "$required_file" ]]; then
        echo "contract policy: required regular file is missing or is a symlink: $required_file" >&2
        exit 1
    fi
done

if [[ ! -f "$manifest" || -L "$manifest" ]]; then
    echo "contract policy: required regular file is missing or is a symlink: $manifest" >&2
    exit 1
fi

(
    cd "$contract_dir"
    sha256sum --check --strict SHA256SUMS
)

verify_source() {
    local name="$1"
    local repository="$2"
    local revision="$3"
    local path="$4"
    local sha256="$5"
    local source_metadata="$contract_dir/$name.SOURCE"

    test "$(wc -l < "$source_metadata" | tr -d ' ')" = 4
    grep -Fx "repository=$repository" "$source_metadata" >/dev/null
    grep -Fx "revision=$revision" "$source_metadata" >/dev/null
    grep -Fx "path=$path" "$source_metadata" >/dev/null
    grep -Fx "sha256=$sha256" "$source_metadata" >/dev/null
}

verify_source \
    application-tracker-service \
    jobseekercopilot/application-tracker-service \
    5f9cfe4ec110a17862031588f5f9f0214b471fd0 \
    contracts/openapi.json \
    cba90d68ee371ba323608243adc231efd17645a40f1b200cc15c82be76f3442d
verify_source \
    authentication-service \
    jobseekercopilot/authentication-service \
    2964aeb07b9861cce555d28cc58c6b9fab1f6107 \
    contracts/openapi.json \
    ce7f707b921a16fb8e53b580032bac4542334ed47f8f07c474e63ba2ecc4c812
verify_source \
    cv-cover-letter-service \
    jobseekercopilot/cv-cover-letter-service \
    6af4144f3292e61d14a25da943604c43a27693d4 \
    contracts/openapi.json \
    c4896052884cf4d3290afa864ec2747b088817a4164a1715bb5e4cbb7a1e6bea
verify_source \
    document-export-service \
    jobseekercopilot/document-export-service \
    b71014fe72d5b3660a95e55facd3d627078d892f \
    contracts/openapi.json \
    17d37926cc6c9dedacb526e018577cb3c1aaf976fdcda24a588d541f9ec4f042
verify_source \
    document-store-service \
    jobseekercopilot/document-store-service \
    e5550c1bbb916f241b89e7cba4635b3b5b25263c \
    contracts/openapi.json \
    e521181e393d87919339d56a40d1fad2ae53d7d6dda2959b90508f405d5968af
verify_source \
    job-service \
    jobseekercopilot/job-service \
    badf3f061732a0bc662722227ee19f877dd463da \
    api/openapi.yaml \
    6465ccfab96a5df67e3bb16a06c3edc4d2a76735b2d789a2237b264636127506
verify_source \
    payment-service \
    jobseekercopilot/payment-service \
    0430cd09fd390a09d5445672504560ffde64cbe4 \
    contracts/openapi.json \
    08312957171b34df832b5b3e62ffba93d68007981ac7c284e8b0bff7de22295a
verify_source \
    user-profile-service \
    jobseekercopilot/user-profile-service \
    806ed6064d10b2de9171b14bd252477c4646de35 \
    api/openapi.json \
    1770e3aed76aa69f16015b496947e9ed2d28fb93f48e21fd4abcb03ec38d680d

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "1.0.0") and
    (.paths["/api/auth/me"].get.operationId == "getCurrentUser") and
    (.paths["/api/auth/me"].get.security as $security
        | ($security | length == 1) and
          ($security[0] | has("bearerAuth") and has("serviceToken"))) and
    (.components.securitySchemes.bearerAuth.type == "http") and
    (.components.securitySchemes.bearerAuth.scheme == "bearer") and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.in == "header") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.paths["/api/auth/me"].get.responses["200"].content["*/*"].schema["$ref"]
        == "#/components/schemas/UserAccountResponse") and
    (.components.schemas.UserAccountResponse.properties
        | has("id") and has("name") and has("email"))
' "$contract_dir/authentication-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "2.1.0") and
    (.paths["/api/profiles/me"].get.operationId == "getMyProfile") and
    (.paths["/api/profiles/me"].get.security | any(has("bearerAuth"))) and
    (.components.securitySchemes.bearerAuth.type == "http") and
    (.components.securitySchemes.bearerAuth.scheme == "bearer") and
    (.components.schemas.UserProfile.properties
        | has("userId") and has("skills") and has("aspirations") and
          has("workPreferences") and has("qualifications") and has("roles")) and
    (.paths["/api/evidence/snapshots"].post.operationId
        == "createEvidenceSnapshot") and
    (.paths["/api/evidence/snapshots/{snapshotId}"].get.operationId
        == "getEvidenceSnapshot") and
    (.components.schemas.EvidenceSnapshotRequest.required
        | index("purpose") != null and index("entryIds") != null and
          index("sectionOrder") != null)
' "$contract_dir/user-profile-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "4.0.0") and
    (.paths["/api/v1/cv-cover-letter/drafts/estimate"].post.operationId
        == "estimateDraft") and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.operationId
        == "generateDraft") and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.security
        | any(has("serviceToken"))) and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.parameters
        | any(
            .name == "X-Document-Owner" and
            .in == "header" and
            .required == true and
            .schema.type == "string")) and
    (.paths["/api/v1/cv-cover-letter/drafts"].post.parameters
        | any(
            .name == "X-Generation-Operation-Id" and
            .in == "header" and
            .required == true)) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.in == "header") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.GenerateRequest.required
        | index("inputSchemaVersion") != null and
          index("profile") != null and index("job") != null) and
    (.components.schemas.GenerateRequest.properties.evidenceSnapshots["$ref"]
        == "#/components/schemas/EvidenceSnapshotsInput") and
    (.components.schemas.DraftGenerationResponse.required
        | index("operationId") != null and index("usage") != null and
          index("cvContent") != null and
          index("coverLetterContent") != null and
          index("claimLedger") != null) and
    (.components.schemas.ValidatedClaimLedger.required
        | index("ledgerId") != null and
          index("ledgerSha256") != null and
          index("claims") != null)
' "$contract_dir/cv-cover-letter-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "3.0.0") and
    (.paths["/api/v1/payments/reservations"].post.parameters
        | any(.name == "X-Payment-Owner" and .required == true)) and
    (.paths["/api/v1/payments/reservations/{reservationId}"].get != null) and
    (.paths["/api/v1/payments/reservations/{reservationId}/commit"].post != null) and
    (.paths["/api/v1/payments/reservations/{reservationId}/release"].post != null) and
    (.components.schemas.CreateReservationRequest.required
        | index("feature") != null and index("operationKey") != null) and
    (.components.schemas.CreateReservationRequest.properties.operationKey.pattern
        == "[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
' "$contract_dir/payment-service.json" >/dev/null

grep -F "  /api/jobs/saved/{savedJobId}:" \
    "$contract_dir/job-service.yaml" >/dev/null
grep -F "        contentVersion:" \
    "$contract_dir/job-service.yaml" >/dev/null
grep -F "        contentSha256:" \
    "$contract_dir/job-service.yaml" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "3.0.0") and
    (.paths["/api/v1/document-exports/documents/{documentId}"].post.operationId
        == "exportDocument") and
    (.paths["/api/v1/document-exports/documents/{documentId}/upload"].post.operationId
        == "uploadReplacement") and
    ([.paths["/api/v1/document-exports/documents/{documentId}"].post,
      .paths["/api/v1/document-exports/documents/{documentId}/upload"].post]
        | all(.security | any(has("serviceToken")))) and
    ([.paths["/api/v1/document-exports/documents/{documentId}"].post,
      .paths["/api/v1/document-exports/documents/{documentId}/upload"].post]
        | all(.parameters | any(
            .name == "X-Document-Owner" and
            .in == "header" and
            .required == true and
            .schema.type == "string"))) and
    (.paths["/api/v1/document-exports/documents/{documentId}/upload"].post.parameters
        | any(
            .name == "Idempotency-Key" and
            .in == "header" and
            .required == false and
            .schema.type == "string")) and
    (.paths["/api/v1/document-exports/documents/{documentId}"].post.parameters
        | any(
            .name == "Idempotency-Key" and
            .in == "header" and
            .required == true and
            .schema.type == "string")) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.in == "header") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.DocumentExportRequest.required | index("formats") != null) and
    (.components.schemas.DocumentExportRequest.properties.formats.items.enum
        | index("DOCX") != null and index("PDF") != null) and
    (.components.schemas.DocumentExportResponse.properties | has("exports")) and
    (.components.schemas.DocumentExportItem.properties
        | has("fileId") and has("format") and has("fileName"))
' "$contract_dir/document-export-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "3.3.0") and
    (.paths["/api/v1/documents/{id}"].get.operationId == "getDocumentById") and
    (.paths["/api/v1/documents"].post.operationId == "createDocument") and
    (.paths["/api/v1/documents/{documentId}/approve"].patch.operationId
        == "approveDocumentVersion") and
    (.paths["/api/v1/document-files/{id}/download"].get.operationId
        == "downloadDocumentFile") and
    (.paths["/api/v1/documents/{generatedDocumentId}/artifacts/{artifactId}/download"].get.operationId
        == "downloadDocumentArtifact") and
    (.paths["/api/v1/documents/families"].get.operationId
        == "listDocumentFamilies") and
    (.paths["/api/v1/documents/families/{documentFamilyId}"].get.operationId
        == "getDocumentFamilyHistory") and
    (.paths["/api/v1/documents/families/{documentFamilyId}/current"].patch.operationId
        == "selectFamilyCurrent") and
    ([.paths["/api/v1/documents/{id}"].get,
      .paths["/api/v1/documents"].post,
      .paths["/api/v1/documents/{documentId}/approve"].patch,
      .paths["/api/v1/document-files/{id}/download"].get]
        | all(.security | any(has("serviceToken")))) and
    ([.paths["/api/v1/documents/{id}"].get,
      .paths["/api/v1/documents"].post,
      .paths["/api/v1/documents/{documentId}/approve"].patch,
      .paths["/api/v1/document-files/{id}/download"].get]
        | all(.parameters | any(
            .name == "X-Document-Owner" and
            .in == "header"))) and
    (.paths["/api/v1/documents"].post.parameters
        | any(
            .name == "Idempotency-Key" and
            .in == "header" and
            .required == false and
            .schema.type == "string")) and
    (.paths["/api/v1/documents/families/{documentFamilyId}/current"].patch.parameters
        | any(
            .name == "Idempotency-Key" and
            .in == "header" and
            .required == true and
            .schema.type == "string")) and
    (.paths["/api/v1/documents/{generatedDocumentId}/artifacts/{artifactId}/download"].get.responses["200"].headers
        | has("Content-Disposition") and has("Content-Length") and
          has("X-Content-Type-Options") and has("Cache-Control") and has("Pragma")) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.in == "header") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.CreateDocumentRequest.required
        | index("userId") != null and index("jobId") != null and
          index("documentType") != null and index("title") != null and
          index("content") != null) and
    (.components.schemas.CreateDocumentRequest.properties
        | has("applicationId") and has("documentFamilyId") and has("active") and
          has("originalFilename") and has("sourceType") and
          has("evidenceProvenance") and has("createdBy")) and
    (.components.schemas.GeneratedDocumentResponse.properties
        | has("id") and has("documentFamilyId") and has("version") and
          has("lifecycleState") and has("evidenceProvenance") and
          has("groundingState") and has("parentDocumentId")) and
    (.components.schemas.DocumentVersionHistoryItem.properties
        | has("documentId") and has("version") and has("source") and
          has("lifecycle") and has("retention") and has("current") and
          has("artifacts") and
          (has("content") | not) and
          (has("contentSha256") | not) and
          (has("originalFilename") | not)) and
    (.components.schemas.DocumentArtifactManifestItem.properties
        | has("artifactId") and has("role") and has("format") and
          has("source") and has("availability") and has("size") and
          (has("fileName") | not) and
          (has("contentSha256") | not)) and
    (.components.schemas.DocumentEvidenceProvenance.required
        | index("profileRevisionId") != null and
          index("profileContentDigest") != null and
          index("evidenceSnapshotId") != null and
          index("evidenceSnapshotDigest") != null and
          index("evidenceRevisions") != null and
          index("sectionOrder") != null and
          index("claimLedger") != null and
          index("generatedAt") != null)
' "$contract_dir/document-store-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "4.5.0") and
    (.paths["/api/v1/applications/{id}"].get.operationId == "getApplicationById") and
    (.paths["/api/v1/applications/document/{documentId}"].get.operationId
        == "getApplicationByDocumentId") and
    (.paths["/api/v1/applications/{id}/status"].patch.operationId
        == "updateStatus") and
    (.paths["/api/v1/applications/{id}/document-selections"].put.operationId
        == "saveDocumentSelections") and
    (.paths["/api/v1/applications/{id}/document-replacements"].post.operationId
        == "beginDocumentReplacement") and
    (.paths["/api/v1/applications/{id}/document-replacements/{operationId}/replacement-document"]
        .patch.operationId == "registerReplacementDocument") and
    (.paths["/api/v1/applications/{id}/document-replacements/{operationId}/complete"]
        .patch.operationId == "completeDocumentReplacement") and
    (.paths["/api/v1/applications/{id}/document-replacements/{operationId}/recovery-required"]
        .patch.operationId == "markDocumentReplacementRecoveryRequired") and
    ([.paths["/api/v1/applications/{id}"].get,
      .paths["/api/v1/applications/document/{documentId}"].get,
      .paths["/api/v1/applications/{id}/status"].patch,
      .paths["/api/v1/applications/{id}/document-replacements"].post,
      .paths["/api/v1/applications/{id}/document-replacements/{operationId}/replacement-document"].patch,
      .paths["/api/v1/applications/{id}/document-replacements/{operationId}/complete"].patch,
      .paths["/api/v1/applications/{id}/document-replacements/{operationId}/recovery-required"].patch]
        | all(.security | any(has("serviceToken")))) and
    (.paths["/api/v1/applications/{id}/status"].patch.parameters
        | any(.name == "X-Application-Owner" and .in == "header") and
          any(.name == "Idempotency-Key" and .in == "header" and
              .schema.maxLength == 128)) and
    (.paths["/api/v1/applications/{id}/document-selections"].put.parameters
        | any(.name == "Idempotency-Key" and .in == "header" and
              .required == true and .schema.maxLength == 128)) and
    (.components.schemas.SaveDocumentSelectionsRequest.required
        | index("cvSelection") != null and
          index("coverLetterSelection") != null and
          index("expectedVersion") != null) and
    (.components.schemas.DocumentSelectionCommand.properties.state.enum
        | index("SELECTED") != null and index("OMITTED") != null) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.BeginDocumentReplacementRequest.required
        | index("documentType") != null and index("requestSha256") != null) and
    (.components.schemas.RegisterReplacementDocumentRequest.required
        | index("replacementDocumentId") != null) and
    (.components.schemas.DocumentReplacementWorkflowResponse.properties
        | has("operationId") and has("sourceDocumentId") and
          has("replacementDocumentId") and has("operationStatus") and
          has("retryable") and has("recoveryCode")) and
    (.components.schemas.ApplicationRecordResponse.properties
        | has("id") and has("userId") and has("jobId") and
          has("cvDocumentId") and has("coverLetterDocumentId") and
          has("cvDocumentReference") and
          has("applicationUsedCvDocumentReference") and
          has("applicationUsedCoverLetterDocumentReference") and
          has("applicationUsedCvState") and
          has("applicationUsedCoverLetterState") and
          has("applicationUsedAt") and has("appliedAt") and has("status")) and
    (.components.schemas.ApplicationRecordResponse.properties.applicationUsedCvState.enum
        | index("UNKNOWN") != null and index("SELECTED") != null and
          index("OMITTED") != null) and
    (.components.schemas.DocumentVersionReference.properties
        | has("contentSha256") and has("evidenceProvenance") and
          has("groundingState"))
' "$contract_dir/application-tracker-service.json" >/dev/null

echo "contract policy: all pinned producer sources are present, intact and compatible"
