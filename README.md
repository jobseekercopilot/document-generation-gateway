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

The User Profile, CV/Cover Letter and Document Export clients are generated
during Maven `generate-sources` from reviewed, checksum-protected producer
contracts under `src/main/openapi`. The raw Document Store and Application
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

```bash
./scripts/test-contract-policy.sh
./scripts/verify-contracts.sh
mvn -B --no-transfer-progress clean verify
docker build --tag local/document-generation-gateway .
```

These commands are the clean-clone verification contract. They require no
sibling repository, local `libs/` directory, generated JAR or preinstalled
Job Seeker Copilot artifact. Tests use mocks and local application endpoints;
they make no live or paid model request.

## Safe local use

Use synthetic fixtures only. Do not use real profiles, CVs, cover letters,
provider credentials, browser sessions, or paid model requests.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).
