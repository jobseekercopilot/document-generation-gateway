# Beta-readiness audit

Audit date: 2026-07-23

Status: **Not ready for private beta**

## Verified responsibility and request flow

The Angular client posts the complete selected job to
`POST /api/v1/document-generation/jobs/{jobId}/generate` through its Node
proxy. The gateway checks a few required fields and the path/body ID match, but
does not load the canonical job even though a Job Service client is configured.
It loads the user profile, optionally enriches contact fields from
Authentication Service, calls the CV and Cover Letter Service, synchronously
exports both documents to DOCX and PDF, and returns download metadata.

The gateway also proxies downloads and replacement uploads. Document Store
owns bytes; Document Export owns binary rendering; CV and Cover Letter Service
owns prompt/domain generation; Application Tracker owns the application
record.

## Migration evidence

- Source was copied from the untracked service directory in the intact root
  workspace; no standalone source history was available.
- The source-controlled JWT fallback was removed from this migration
  candidate. `JWT_SECRET` is now required and no secret value was copied.
- `target/`, local client JARs, generated binaries, logs, databases, exported
  documents, recordings, and environment files are excluded.
- The migration-time contract is `contracts/openapi.json`.
- Gitleaks and targeted personal-data checks passed on the sanitised source.
- The DOCGEN-02 gateway slice replaces the three source-used `systemPath`
  clients with deterministic generation from exact revision/checksum-pinned
  producer contracts. It removes four unused generated-client dependencies
  and two unused generated API beans. Contract policy tests, Maven
  verification and the source-only container build run in CI without sibling
  repositories, local `libs/` or preinstalled Job Seeker Copilot artifacts.
- Producer contract compatibility does not resolve trusted downstream
  identity. User Profile requires bearer authentication and Application
  Tracker requires bearer or service-token authentication with owner context;
  GW-01 remains the direct blocker. Authentication Service has no
  producer-owned OpenAPI artifact for `/api/auth/me`; DOCGEN-03 owns that gap.
- OWASP Dependency-Check 12.1.8 completed against the cached 2026-07-18
  advisory database: 62 dependencies, 14 vulnerable dependencies, 146
  vulnerability matches, including 17 Critical and 41 High matches. Results
  require reachability/false-positive triage; the report was not committed.

## Confirmed blockers

1. `JwtTokenFilter` accepts a caller-controlled `X-User-Id` fallback, allowing
   untrusted identity selection.
2. The generation request trusts browser-supplied job title, employer, and
   description instead of resolving the selected canonical job.
3. The generation, export, and application path is synchronous and non-atomic;
   it has no operation state, idempotency key, cancellation, or safe retry.
4. Generated documents are already stored and linked as
   `DOCUMENTS_GENERATED` before user preview/edit/approval.
5. File download by UUID is proxied without an ownership check.
6. Direct document replacement is not authorised against the authenticated
   owner.
7. Application-based replacement accepts missing identity and relies on an
   unauthenticated downstream lookup.
8. Upload validation checks extension/MIME/basic ZIP members only; it lacks
   bounded decompression, macro/relationship/content checks, and filename
   hardening evidence.
9. Downstream `RestTemplate` orchestration has no consistent timeout budget,
   resilience policy, or provider-safe error contract.
10. Full profile contact data is added to the generation payload without an
    explicit data-minimisation contract.
11. There is no complete correlation-safe state model or metrics for
    generation, invalid output, rejected claims, cost, storage, export, and
    download failures.
12. The six Java service boundaries now have immutable producer contract pins,
    but full-fleet publication and TypeScript generation evidence remain
    incomplete.
13. The Dockerfile is not hardened with digest-pinned bases, a non-root
    runtime, explicit readiness, resource limits, or supply-chain evidence.
14. Current Spring, Tomcat, Jackson, security, HTTP, compression, POI,
    logging, and Swagger UI dependency findings include untriaged
    Critical/High advisories.

## Required validation

Before beta, evidence must cover trusted identity and canonical job loading,
ownership enforcement on every operation, explicit draft/review/approval
states, idempotent orchestration and recovery, bounded uploads and downstream
calls, reproducible clients, failure-safe UI behaviour, cross-user negative
tests, and a complete deterministic browser-to-export journey.

The local root Compose file contains development-only configuration and was
not migrated. No deployment was performed.
