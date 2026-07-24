#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT

copy_contracts() {
    local destination="$1"
    mkdir -p "$destination"
    cp "$repository_root"/src/main/openapi/*.json \
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
jq 'del(.components.schemas.GenerateCvCoverLetterResponse.properties.applicationId)' \
    "$temporary_dir/cv-response/cv-cover-letter-service.json" \
    > "$temporary_dir/cv-response/changed.json"
mv "$temporary_dir/cv-response/changed.json" \
   "$temporary_dir/cv-response/cv-cover-letter-service.json"
refresh_manifest "$temporary_dir/cv-response"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/cv-response" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of generated application ID" >&2
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

copy_contracts "$temporary_dir/store-operation"
jq 'del(.paths["/api/v1/documents/applications/{applicationId}/{documentType}/active/{documentId}"].patch)' \
    "$temporary_dir/store-operation/document-store-service.json" \
    > "$temporary_dir/store-operation/changed.json"
mv "$temporary_dir/store-operation/changed.json" \
   "$temporary_dir/store-operation/document-store-service.json"
refresh_manifest "$temporary_dir/store-operation"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/store-operation" >/dev/null 2>&1; then
    echo "contract policy negative test accepted removal of document activation" >&2
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
sed 's/revision=86c8510/revision=0000000/' \
    "$temporary_dir/source-revision/user-profile-service.SOURCE" \
    > "$temporary_dir/source-revision/changed.SOURCE"
mv "$temporary_dir/source-revision/changed.SOURCE" \
   "$temporary_dir/source-revision/user-profile-service.SOURCE"
if "$repository_root/scripts/verify-contracts.sh" "$temporary_dir/source-revision" >/dev/null 2>&1; then
    echo "contract policy negative test accepted unreviewed producer revision metadata" >&2
    exit 1
fi

echo "contract policy tests passed"
