# Document Generation Gateway

Browser-facing orchestration gateway for generating, exporting, downloading,
and replacing CV and cover-letter documents.

This service is **not beta-ready**. A new durable saved-job operation API now
resolves canonical snapshots, coordinates AI Credit, stores drafts and waits
for exact-version approval before export and Tracker creation. Version 2.0
removes the unsafe browser-supplied Job route: all new generation now requires
a saved canonical job and explicit CV and cover-letter evidence selections.
Runtime database and cross-user E2E evidence remain outstanding. See
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

Version `2.4.0` exposes owner-scoped per-version archive, restore and
recoverable-delete orchestration plus content-free draft/frozen application
associations. Family history carries `PURGED` tombstones, stable unavailable
reason/timestamps and exact application/freeze identity without content,
filenames, complete hashes or evidence payloads. No public purge endpoint is
introduced.
Version `2.3.0` consumes Application Tracker `4.3.0` and carries its explicit
frozen `SELECTED`, `OMITTED` and legacy `UNKNOWN` slot states, exact frozen
references and one freeze/application time through safe Gateway response
models. APPLIED remains a bearer-owned lifecycle command; the Gateway's
generation producer identity is still limited to document preparation.
Version `2.2.0` adds one authenticated, explicit, expected-version and
idempotency-protected application selection command for CV, cover letter and
intentional omission. Durable generation recovery uses the same atomic Tracker
contract instead of two sequential slot writes. It also adds exact
owner-authorised retained-artifact downloads and
preserves canonical MIME, attachment, length, `nosniff`, private/no-store and
`Pragma` policy through the Gateway without changing document state.
Version `2.1.0` adds authenticated content-free family paging, newest-first
server-numbered history, exact safe artifact manifests and explicit
concurrency/idempotency-protected current selection using Document Store 3.0.
Version `2.0.0` requires separate claimant-selected entry and section order
for CV and cover-letter generation, resolves immutable evidence snapshots and
removes the legacy browser-supplied Job route. Version `1.4.0` validates active
canonical Job 2.0 snapshot evidence and
applies the operation's remaining absolute deadline to every durable-flow
downstream HTTP connect/read. Version `1.3.0` added the durable
initial-generation operation described in
[`docs/DOCGEN-09_DURABLE_COORDINATOR.md`](docs/DOCGEN-09_DURABLE_COORDINATOR.md).
It retains the version `1.2.0` recoverable application-document replacement
response. The Gateway reserves a Tracker operation before Store writes, creates
the new version in the existing document family, uses stable idempotency keys
for Store and Export, and returns `202` with operation/recovery state whenever
completion is still pending. Tracker alone commits the application reference.

The User Profile, CV/Cover Letter and Document Export clients resolve
as immutable, producer-owned private Maven packages
`1.3.0-rev.85ac64be8c54`, `3.4.0-rev.fed6400b706b` and
`2.0.0-rev.a35fff34f86b`. The Gateway no longer generates Java clients inside
the consumer build. The new durable flow uses focused handwritten adapters for
Job `2.0.0` and Payment `3.0.0`, plus producer-owned clients for User Profile
`1.3.0` and CV/Cover Letter `3.4.0`. The raw
Authentication, Document Store, Application Tracker and replacement-upload
adapters are also checked against their pinned producer contracts. The durable
path consumes Store `3.3.0`, Application Tracker `4.5.0` and Export `3.0.0`
through focused handwritten
adapters, while replacement flows retain the immutable Export `2.0.0` client.
Generated sources and binaries are build output and are not committed. See
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
DOCUMENT_GENERATION_GATEWAY_TO_PAYMENT_SERVICE_TOKEN
DOCUMENT_GENERATION_DATABASE_URL
DOCUMENT_GENERATION_DATABASE_USERNAME
DOCUMENT_GENERATION_DATABASE_PASSWORD
DOCUMENT_GENERATION_OPERATION_DEADLINE
DOCUMENT_GENERATION_OPERATION_LEASE
DOCUMENT_GENERATION_CONNECT_TIMEOUT
DOCUMENT_GENERATION_READ_TIMEOUT
DOCUMENT_METADATA_REQUESTS_PER_MINUTE
DOCUMENT_DOWNLOADS_PER_MINUTE
```

The operation lease must be longer than the downstream read timeout. The
defaults are a three-minute lease and a two-minute read timeout so a bounded
model invocation can complete without another request taking ownership of the
same paid operation.

Document family metadata/current commands and exact downloads are limited per
authenticated owner in each Gateway instance. Defaults are 120 metadata
requests and 30 downloads per minute; excess requests return `429` with a
bounded `Retry-After` value and do not call Document Store.

The seven service tokens must be pairwise distinct and contain at least 32 bytes. They
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
