# Document Generation Gateway

Browser-facing orchestration gateway for generating, exporting, downloading,
and replacing CV and cover-letter documents.

This service is **not beta-ready**. The gateway currently accepts
browser-supplied job data and a fallback user identity, does not enforce
document ownership on all file operations, runs a long synchronous non-atomic
workflow, and has unresolved downstream identity boundaries. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the migration-time
OpenAPI snapshot. Publishing a producer-owned contract and proving downstream
compatibility remain tracked beta-readiness work.

The User Profile and CV/Cover Letter clients resolve as immutable,
producer-owned private Maven packages `1.0.0-rev.86c8510ed319` and
`1.0.0-rev.68b4cf9d3f23`. Only the Document Export client is still generated
during Maven `generate-sources` from its reviewed, checksum-protected producer
contract under `src/main/openapi`. The raw Document Store and Application
Tracker adapters are checked against their pinned producer contracts.
Generated sources and binaries are build output and are not committed. See
[`docs/CONTRACT_GOVERNANCE.md`](docs/CONTRACT_GOVERNANCE.md).

Authentication Service has no producer-owned OpenAPI artifact for the
handwritten `/api/auth/me` boundary. DOCGEN-03 tracks that gap; this repository
does not manufacture a consumer-owned substitute.

## Configuration

`JWT_SECRET` is required. The service must fail closed if authentication
configuration is absent; no secret has a source-controlled default.

## Build

Docker Buildx is required because the private Maven credential must reach the
build through BuildKit secret mounts. Verify it with `docker buildx version`;
the legacy Docker builder is intentionally unsupported.

```bash
./scripts/test-contract-policy.sh
./scripts/verify-contracts.sh
python3 scripts/verify_package_consumer.py
python3 scripts/test_package_consumer_policy.py
JSC_PACKAGE_READ_TOKEN=... mvn -B --no-transfer-progress \
  -s .mvn/github-packages-settings.xml clean verify
JSC_PACKAGE_READ_TOKEN=... ./scripts/build-container.sh \
  local/document-generation-gateway
```

These commands are the clean-clone verification contract. They require no
sibling repository, local `libs/` directory, generated JAR or preinstalled
Job Seeker Copilot artifact. The dedicated classic package-read token requires
`read:packages` and private-repository access; it remains untracked and reaches
the container build only through BuildKit secrets. Tests use mocks and local
application endpoints; they make no live or paid model request.

## Safe local use

Use synthetic fixtures only. Do not use real profiles, CVs, cover letters,
provider credentials, browser sessions, or paid model requests.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).
