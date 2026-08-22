#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

copy_contracts() {
    local destination="$1"
    mkdir -p "$destination"
    cp "$repository_root"/src/main/openapi/*.json \
       "$repository_root"/src/main/openapi/*.yaml \
       "$repository_root"/src/main/openapi/*.SOURCE \
       "$repository_root/src/main/openapi/SHA256SUMS" \
       "$destination/"
}

refresh_manifest() {
    local directory="$1"
    (
        cd "$directory"
        sha256sum \
            application-tracker-service.json \
            authentication-service.json \
            cv-cover-letter-service.json \
            document-export-service.json \
            document-store-service.json \
            job-service.yaml \
            payment-service.json \
            user-profile-service.json \
            > SHA256SUMS
    )
}

"$repository_root/scripts/verify-contracts.sh" "$repository_root/src/main/openapi" >/dev/null

copy_contracts "$temporary_dir/missing"
rm "$temporary_dir/missing/user-profile-service.json"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/missing" >/dev/null 2>&1; then
    echo "contract policy negative test accepted a missing producer contract" >&2
    exit 1
fi

copy_contracts "$temporary_dir/checksum-drift"
jq '.info.description = "unreviewed drift"' \
    "$temporary_dir/checksum-drift/cv-cover-letter-service.json" \
    > "$temporary_dir/checksum-drift/changed.json"
mv "$temporary_dir/checksum-drift/changed.json" \
   "$temporary_dir/checksum-drift/cv-cover-letter-service.json"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/checksum-drift" >/dev/null 2>&1; then
    echo "contract policy negative test accepted checksum drift" >&2
    exit 1
fi

copy_contracts "$temporary_dir/profile-security"
jq 'del(.components.securitySchemes.bearerAuth)' \
    "$temporary_dir/profile-security/user-profile-service.json" \
    > "$temporary_dir/profile-security/changed.json"
mv "$temporary_dir/profile-security/changed.json" \
   "$temporary_dir/profile-security/user-profile-service.json"
refresh_manifest "$temporary_dir/profile-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/profile-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of profile bearer security" >&2
    exit 1
fi

copy_contracts "$temporary_dir/cv-response"
jq '.components.schemas.DraftGenerationResponse.required |=
        map(select(. != "operationId"))' \
    "$temporary_dir/cv-response/cv-cover-letter-service.json" \
    > "$temporary_dir/cv-response/changed.json"
mv "$temporary_dir/cv-response/changed.json" \
   "$temporary_dir/cv-response/cv-cover-letter-service.json"
refresh_manifest "$temporary_dir/cv-response"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/cv-response" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of draft operation ID" >&2
    exit 1
fi

copy_contracts "$temporary_dir/cv-security"
jq 'del(
        .paths["/api/v1/cv-cover-letter/drafts"].post.security,
        .components.securitySchemes.serviceToken
    )' \
    "$temporary_dir/cv-security/cv-cover-letter-service.json" \
    > "$temporary_dir/cv-security/changed.json"
mv "$temporary_dir/cv-security/changed.json" \
   "$temporary_dir/cv-security/cv-cover-letter-service.json"
refresh_manifest "$temporary_dir/cv-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/cv-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of CV service identity" >&2
    exit 1
fi

copy_contracts "$temporary_dir/cv-owner"
jq '.paths["/api/v1/cv-cover-letter/drafts"].post.parameters |=
        map(select(.name != "X-Document-Owner"))' \
    "$temporary_dir/cv-owner/cv-cover-letter-service.json" \
    > "$temporary_dir/cv-owner/changed.json"
mv "$temporary_dir/cv-owner/changed.json" \
   "$temporary_dir/cv-owner/cv-cover-letter-service.json"
refresh_manifest "$temporary_dir/cv-owner"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/cv-owner" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of CV owner context" >&2
    exit 1
fi

copy_contracts "$temporary_dir/payment-owner"
jq '.paths["/api/v1/payments/reservations"].post.parameters |=
        map(select(.name != "X-Payment-Owner"))' \
    "$temporary_dir/payment-owner/payment-service.json" \
    > "$temporary_dir/payment-owner/changed.json"
mv "$temporary_dir/payment-owner/changed.json" \
   "$temporary_dir/payment-owner/payment-service.json"
refresh_manifest "$temporary_dir/payment-owner"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/payment-owner" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Payment owner context" >&2
    exit 1
fi

copy_contracts "$temporary_dir/job-snapshot"
sed '/        contentSha256:/d' \
    "$temporary_dir/job-snapshot/job-service.yaml" \
    > "$temporary_dir/job-snapshot/changed.yaml"
mv "$temporary_dir/job-snapshot/changed.yaml" \
   "$temporary_dir/job-snapshot/job-service.yaml"
refresh_manifest "$temporary_dir/job-snapshot"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/job-snapshot" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Job snapshot digest" >&2
    exit 1
fi

copy_contracts "$temporary_dir/export-operation"
jq 'del(.paths["/api/v1/document-exports/documents/{documentId}"].post)' \
    "$temporary_dir/export-operation/document-export-service.json" \
    > "$temporary_dir/export-operation/changed.json"
mv "$temporary_dir/export-operation/changed.json" \
   "$temporary_dir/export-operation/document-export-service.json"
refresh_manifest "$temporary_dir/export-operation"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/export-operation" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of document export" >&2
    exit 1
fi

copy_contracts "$temporary_dir/export-security"
jq 'del(.components.securitySchemes.serviceToken)' \
    "$temporary_dir/export-security/document-export-service.json" \
    > "$temporary_dir/export-security/changed.json"
mv "$temporary_dir/export-security/changed.json" \
   "$temporary_dir/export-security/document-export-service.json"
refresh_manifest "$temporary_dir/export-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/export-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Export service identity" >&2
    exit 1
fi

copy_contracts "$temporary_dir/export-owner"
jq '.paths["/api/v1/document-exports/documents/{documentId}"].post.parameters |=
        map(select(.name != "X-Document-Owner"))' \
    "$temporary_dir/export-owner/document-export-service.json" \
    > "$temporary_dir/export-owner/changed.json"
mv "$temporary_dir/export-owner/changed.json" \
   "$temporary_dir/export-owner/document-export-service.json"
refresh_manifest "$temporary_dir/export-owner"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/export-owner" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Export owner context" >&2
    exit 1
fi

copy_contracts "$temporary_dir/export-idempotency"
jq '.paths["/api/v1/document-exports/documents/{documentId}"].post.parameters |=
        map(select(.name != "Idempotency-Key"))' \
    "$temporary_dir/export-idempotency/document-export-service.json" \
    > "$temporary_dir/export-idempotency/changed.json"
mv "$temporary_dir/export-idempotency/changed.json" \
   "$temporary_dir/export-idempotency/document-export-service.json"
refresh_manifest "$temporary_dir/export-idempotency"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/export-idempotency" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of ordinary Export replay key" >&2
    exit 1
fi

copy_contracts "$temporary_dir/store-operation"
jq 'del(.paths["/api/v1/documents/{documentId}/approve"].patch)' \
    "$temporary_dir/store-operation/document-store-service.json" \
    > "$temporary_dir/store-operation/changed.json"
mv "$temporary_dir/store-operation/changed.json" \
   "$temporary_dir/store-operation/document-store-service.json"
refresh_manifest "$temporary_dir/store-operation"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/store-operation" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of document approval" >&2
    exit 1
fi

copy_contracts "$temporary_dir/store-idempotency"
jq '.paths["/api/v1/documents"].post.parameters |=
        map(select(.name != "Idempotency-Key"))' \
    "$temporary_dir/store-idempotency/document-store-service.json" \
    > "$temporary_dir/store-idempotency/changed.json"
mv "$temporary_dir/store-idempotency/changed.json" \
   "$temporary_dir/store-idempotency/document-store-service.json"
refresh_manifest "$temporary_dir/store-idempotency"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/store-idempotency" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Store replay key" >&2
    exit 1
fi

copy_contracts "$temporary_dir/store-family"
jq 'del(.components.schemas.CreateDocumentRequest.properties.documentFamilyId)' \
    "$temporary_dir/store-family/document-store-service.json" \
    > "$temporary_dir/store-family/changed.json"
mv "$temporary_dir/store-family/changed.json" \
   "$temporary_dir/store-family/document-store-service.json"
refresh_manifest "$temporary_dir/store-family"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/store-family" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Store family continuity" >&2
    exit 1
fi

copy_contracts "$temporary_dir/store-security"
jq 'del(.components.securitySchemes.serviceToken)' \
    "$temporary_dir/store-security/document-store-service.json" \
    > "$temporary_dir/store-security/changed.json"
mv "$temporary_dir/store-security/changed.json" \
   "$temporary_dir/store-security/document-store-service.json"
refresh_manifest "$temporary_dir/store-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/store-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Store service identity" >&2
    exit 1
fi

copy_contracts "$temporary_dir/store-owner"
jq '.paths["/api/v1/document-files/{id}/download"].get.parameters |=
        map(select(.name != "X-Document-Owner"))' \
    "$temporary_dir/store-owner/document-store-service.json" \
    > "$temporary_dir/store-owner/changed.json"
mv "$temporary_dir/store-owner/changed.json" \
   "$temporary_dir/store-owner/document-store-service.json"
refresh_manifest "$temporary_dir/store-owner"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/store-owner" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Store owner context" >&2
    exit 1
fi

copy_contracts "$temporary_dir/store-secure-upload"
jq 'del(.paths["/api/v1/applications/{applicationId}/documents/{documentType}/uploads"].post)' \
    "$temporary_dir/store-secure-upload/document-store-service.json" \
    > "$temporary_dir/store-secure-upload/changed.json"
mv "$temporary_dir/store-secure-upload/changed.json" \
   "$temporary_dir/store-secure-upload/document-store-service.json"
refresh_manifest "$temporary_dir/store-secure-upload"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/store-secure-upload" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of secure application upload" >&2
    exit 1
fi

copy_contracts "$temporary_dir/tracker-security"
jq 'del(.components.securitySchemes.serviceToken)' \
    "$temporary_dir/tracker-security/application-tracker-service.json" \
    > "$temporary_dir/tracker-security/changed.json"
mv "$temporary_dir/tracker-security/changed.json" \
   "$temporary_dir/tracker-security/application-tracker-service.json"
refresh_manifest "$temporary_dir/tracker-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/tracker-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of tracker service identity" >&2
    exit 1
fi

copy_contracts "$temporary_dir/tracker-replacement"
jq 'del(.paths["/api/v1/applications/{id}/document-replacements"].post)' \
    "$temporary_dir/tracker-replacement/application-tracker-service.json" \
    > "$temporary_dir/tracker-replacement/changed.json"
mv "$temporary_dir/tracker-replacement/changed.json" \
   "$temporary_dir/tracker-replacement/application-tracker-service.json"
refresh_manifest "$temporary_dir/tracker-replacement"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/tracker-replacement" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of Tracker replacement workflow" >&2
    exit 1
fi

copy_contracts "$temporary_dir/auth-operation"
jq 'del(.paths["/api/auth/me"].get)' \
    "$temporary_dir/auth-operation/authentication-service.json" \
    > "$temporary_dir/auth-operation/changed.json"
mv "$temporary_dir/auth-operation/changed.json" \
   "$temporary_dir/auth-operation/authentication-service.json"
refresh_manifest "$temporary_dir/auth-operation"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/auth-operation" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of authentication lookup" >&2
    exit 1
fi

copy_contracts "$temporary_dir/auth-response"
jq 'del(.components.schemas.UserAccountResponse.properties.email)' \
    "$temporary_dir/auth-response/authentication-service.json" \
    > "$temporary_dir/auth-response/changed.json"
mv "$temporary_dir/auth-response/changed.json" \
   "$temporary_dir/auth-response/authentication-service.json"
refresh_manifest "$temporary_dir/auth-response"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/auth-response" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of consumed authentication data" >&2
    exit 1
fi

copy_contracts "$temporary_dir/auth-security"
jq '.paths["/api/auth/me"].get.security = [
        {"bearerAuth": []},
        {"serviceToken": []}
    ]' \
    "$temporary_dir/auth-security/authentication-service.json" \
    > "$temporary_dir/auth-security/changed.json"
mv "$temporary_dir/auth-security/changed.json" \
   "$temporary_dir/auth-security/authentication-service.json"
refresh_manifest "$temporary_dir/auth-security"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/auth-security" >/dev/null 2>&1; then
    echo "contract policy negative test accepted weakening authentication identity to OR" >&2
    exit 1
fi

copy_contracts "$temporary_dir/source-revision"
sed 's/^revision=.*/revision=0000000/' \
    "$temporary_dir/source-revision/user-profile-service.SOURCE" \
    > "$temporary_dir/source-revision/changed.SOURCE"
mv "$temporary_dir/source-revision/changed.SOURCE" \
   "$temporary_dir/source-revision/user-profile-service.SOURCE"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/source-revision" >/dev/null 2>&1; then
    echo "contract policy negative test accepted unreviewed producer revision metadata" >&2
    exit 1
fi

echo "contract policy tests passed"
