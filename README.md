# Document Generation Gateway

Browser-facing orchestration gateway for generating, exporting, downloading,
and replacing CV and cover-letter documents.

This migration baseline is **not beta-ready**. The gateway currently accepts
browser-supplied job data and a fallback user identity, does not enforce
document ownership on all file operations, runs a long synchronous non-atomic
workflow, and cannot build from a clean clone. See
[`docs/BETA_READINESS_AUDIT.md`](docs/BETA_READINESS_AUDIT.md).

## Technology

- Java 17
- Spring Boot 3.2.0
- Maven

## API contract

[`contracts/openapi.json`](contracts/openapi.json) is the migration-time
OpenAPI snapshot. Contract publication and reproducible client generation are
tracked as beta blockers.

## Configuration

`JWT_SECRET` is required. The service must fail closed if authentication
configuration is absent; no secret has a source-controlled default.

## Build

```bash
mvn -B clean verify
```

The command currently fails in a clean clone because generated service clients
are referenced from an untracked local `libs/` directory. Compiled clients must
not be committed as the fix.

## Safe local use

Use synthetic fixtures only. Do not use real profiles, CVs, cover letters,
provider credentials, browser sessions, or paid model requests.

## Licence

Copyright © 2026 Bernard McGeever. All rights reserved.

This repository contains proprietary software belonging to Bernard McGeever.
It may not be used, copied, modified or distributed without express written
permission. See [LICENSE](./LICENSE).
