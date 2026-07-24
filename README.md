# Document Generation Gateway

Browser-facing orchestration gateway for generating, exporting, downloading,
and replacing CV and cover-letter documents.

This service is **not beta-ready**. The gateway accepts browser-supplied job
data and runs a long synchronous non-atomic workflow. Its six direct service
boundaries now bind reviewed credentials and owner context where required, but
runtime fleet wiring and cross-user E2E evidence remain outstanding. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

The cross-repository document ownership boundary is defined by the accepted
[Document architecture ADR](https://github.com/jobseekercopilot/document-store-service/blob/fedcdbdec63795269c4e4c4f43fc32f38c6327b1/docs/adr/0001-document-architecture-and-ownership.md).
Gateway's source/OpenAPI/Compose review, current and target sequence diagrams,
state-owner map, repository decisions and deterministic synthetic trace are in
[`docs/architecture/DOCGEN-01_VERIFICATION.md`](docs/architecture/DOCGEN-01_VERIFICATION.md).
This is an implementation boundary, not a beta-readiness claim.

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the migration-time
OpenAPI snapshot. Publishing a producer-owned contract and proving downstream
compatibility remain tracked beta-readiness work.

The User Profile, CV/Cover Letter and Document Export clients resolve as
immutable, producer-owned private Maven packages
`1.0.0-rev.86c8510ed319`, `2.0.0-rev.87fc2393309a` and
`2.0.0-rev.a35fff34f86b`. The Gateway no longer generates Java clients inside
the consumer build. The raw Authentication, Document Store and Application
Tracker adapters are checked against their pinned producer contracts. Generated
sources and binaries are build output and are not committed. See
[`docs/CONTRACT_GOVERNANCE.md`](docs/CONTRACT_GOVERNANCE.md).

Authentication Service's producer-owned `/api/auth/me` contract is pinned to
merged revision `2964aeb07b9861cce555d28cc58c6b9fab1f6107`. The handwritten
adapter is verified against the exact operation, response and combined identity
requirements without manufacturing an unused client.

## Configuration

The gateway validates Authentication Service RS256 access tokens through its
JWKS endpoint. It requires configured issuer, audience, Authentication Service
identity and Application Tracker producer identity values:

```text
AUTH_JWKS_URI
DOCUMENT_GENERATION_JWT_ISSUER
DOCUMENT_GENERATION_JWT_AUDIENCE
AUTH_SERVICE_TOKEN
APPLICATION_TRACKER_PRODUCER_TOKEN
CV_COVER_LETTER_GATEWAY_TOKEN
DOCUMENT_EXPORT_GATEWAY_TOKEN
DOCUMENT_STORE_PRODUCER_TOKEN
DOCUMENT_STORE_READER_TOKEN
```

The six service tokens must be pairwise distinct and contain at least 32 bytes. They
have no source-controlled runtime default. See
[`docs/IDENTITY_BOUNDARY.md`](docs/IDENTITY_BOUNDARY.md).

## Build

Docker Buildx is required because the private Maven credential must reach the
build through BuildKit secret mounts. Verify it with `docker buildx version`;
the legacy Docker builder is intentionally unsupported.

```bash
python3 scripts/verify_architecture.py
python3 scripts/test_architecture_policy.py
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
