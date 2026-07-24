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
    d9e6bc9fcbe4ef665334c58672c8062b1e4796aa \
    contracts/openapi.json \
    549cebba300c2caf3403b9de01d3c34de84464a280a02183751e8a9583ad982a
verify_source \
    authentication-service \
    jobseekercopilot/authentication-service \
    2964aeb07b9861cce555d28cc58c6b9fab1f6107 \
    contracts/openapi.json \
    ce7f707b921a16fb8e53b580032bac4542334ed47f8f07c474e63ba2ecc4c812
verify_source \
    cv-cover-letter-service \
    jobseekercopilot/cv-cover-letter-service \
    68b4cf9d3f2395abd642180a204db3a67d9ae80e \
    contracts/openapi.json \
    c493db389a3255c29cc250d14ede2e8a1a03c7efe22e340ccc009584ab4e225b
verify_source \
    document-export-service \
    jobseekercopilot/document-export-service \
    a35fff34f86b77457df4b9e324000a32819d5aba \
    contracts/openapi.json \
    e696b76efc05149778d1a31df684b6ba5687120396fb6e000ce4f888346f5072
verify_source \
    document-store-service \
    jobseekercopilot/document-store-service \
    b696fe81e9b900e0749e185f595ff4c98c24119d \
    contracts/openapi.json \
    3d0595c83cc66d9037e08af6a4b087c115c9a5d99ec71491f1aa5fc3afffd6ba
verify_source \
    user-profile-service \
    jobseekercopilot/user-profile-service \
    86c8510ed319a059b991e6f9f1e43b0e101c5d1f \
    api/openapi.json \
    ffaaa16a169ab11d864f82440be9fcc7d5df2d4f2d63a3525d40bda497ea6598

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
    (.info.version == "1.0.0") and
    (.paths["/api/profiles/me"].get.operationId == "getMyProfile") and
    (.paths["/api/profiles/me"].get.security | any(has("bearerAuth"))) and
    (.components.securitySchemes.bearerAuth.type == "http") and
    (.components.securitySchemes.bearerAuth.scheme == "bearer") and
    (.components.schemas.UserProfile.properties
        | has("userId") and has("skills") and has("aspirations") and
          has("workPreferences") and has("qualifications") and has("roles"))
' "$contract_dir/user-profile-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "1.0.0") and
    (.paths["/api/v1/cv-cover-letter/generate"].post.operationId == "generate") and
    (.components.schemas.GenerateRequest.required
        | index("userProfile") != null and index("job") != null) and
    (.components.schemas.Job.required
        | index("id") != null and index("title") != null and
          index("company") != null and index("description") != null) and
    (.components.schemas.GenerateCvCoverLetterResponse.properties
        | has("applicationId") and has("cvDocumentId") and
          has("coverLetterDocumentId"))
' "$contract_dir/cv-cover-letter-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "2.0.0") and
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
    (.info.version == "1.1.0") and
    (.paths["/api/v1/documents/{id}"].get.operationId == "getDocumentById") and
    (.paths["/api/v1/documents"].post.operationId == "createDocument") and
    (.paths["/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}"]
        .patch.operationId == "activateDocumentVersion") and
    (.paths["/api/v1/document-files/{id}/download"].get.operationId
        == "downloadDocumentFile") and
    ([.paths["/api/v1/documents/{id}"].get,
      .paths["/api/v1/documents"].post,
      .paths["/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}"].patch,
      .paths["/api/v1/document-files/{id}/download"].get]
        | all(.security | any(has("serviceToken")))) and
    ([.paths["/api/v1/documents/{id}"].get,
      .paths["/api/v1/documents"].post,
      .paths["/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}"].patch,
      .paths["/api/v1/document-files/{id}/download"].get]
        | all(.parameters | any(
            .name == "X-Document-Owner" and
            .in == "header"))) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.in == "header") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.CreateDocumentRequest.required
        | index("userId") != null and index("jobId") != null and
          index("documentType") != null and index("title") != null and
          index("content") != null) and
    (.components.schemas.CreateDocumentRequest.properties
        | has("applicationId") and has("active") and
          has("originalFilename") and has("sourceType") and has("createdBy")) and
    (.components.schemas.GeneratedDocumentResponse.properties
        | has("id") and has("version"))
' "$contract_dir/document-store-service.json" >/dev/null

jq -e '
    (.openapi | type == "string" and startswith("3.")) and
    (.info.version == "1.1.0") and
    (.paths["/api/v1/applications/{id}"].get.operationId == "getApplicationById") and
    (.paths["/api/v1/applications/document/{documentId}"].get.operationId
        == "getApplicationByDocumentId") and
    (.paths["/api/v1/applications/{id}/document-reference"].patch.operationId
        == "updateDocumentReference") and
    ([.paths["/api/v1/applications/{id}"].get,
      .paths["/api/v1/applications/document/{documentId}"].get,
      .paths["/api/v1/applications/{id}/document-reference"].patch]
        | all(.security | any(has("serviceToken")))) and
    (.components.securitySchemes.serviceToken.type == "apiKey") and
    (.components.securitySchemes.serviceToken.name == "X-Service-Token") and
    (.components.schemas.UpdateDocumentReferenceRequest.required
        | index("documentType") != null and index("documentId") != null) and
    (.components.schemas.ApplicationRecordResponse.properties
        | has("id") and has("userId") and has("jobId") and
          has("cvDocumentId") and has("coverLetterDocumentId") and has("status"))
' "$contract_dir/application-tracker-service.json" >/dev/null

echo "contract policy: all pinned producer sources are present, intact and compatible"
