#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image_name="${1:-local/document-generation-gateway}"

if [[ -z "${JSC_PACKAGE_READ_TOKEN:-}" ]]; then
    echo "Container build requires JSC_PACKAGE_READ_TOKEN for the private Maven package" >&2
    exit 1
fi

umask 077
token_file="$(mktemp)"
trap 'rm -f "$token_file"' EXIT
printf '%s' "$JSC_PACKAGE_READ_TOKEN" > "$token_file"

docker buildx build --load \
    --secret "id=maven_settings,src=$repository_root/.mvn/github-packages-settings.xml" \
    --secret "id=package_token,src=$token_file" \
    --tag "$image_name" \
    "$repository_root"
