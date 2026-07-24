# Beta-readiness audit

Audit date: 2026-07-24

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
- The source-controlled JWT fallback was removed from the migration candidate.
  The later GW-01 slice replaces the legacy HMAC filter with the platform
  RS256/JWKS contract and keeps signing and service-identity secrets out of
  source.
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
- Producer contract compatibility does not resolve every trusted downstream
  identity. The GW-01 gateway slice now forwards only the validated bearer to
  User Profile, adds runtime service identity to Authentication and adds
  runtime producer identity plus owner context to Application Tracker.
  The Store consumer slice pins Store 1.1.0 and adds distinct reader/producer
  identities plus owner context to each direct Store operation. The Export
  consumer slice pins Export 2.0.0 and adds its distinct service identity plus
  owner context to generation and replacement calls. The final CV/Cover Letter
  consumer slice pins CV/Cover Letter 2.0.0, adds a sixth distinct service
  identity, and binds generation to the validated owner without forwarding
  legacy `X-User-Id`.
- OWASP Dependency-Check 12.1.8 completed against the cached 2026-07-18
  advisory database: 62 dependencies, 14 vulnerable dependencies, 146
  vulnerability matches, including 17 Critical and 41 High matches. Results
  require reachability/false-positive triage; the report was not committed.

## Confirmed blockers

1. Resolved in the GW-01 gateway slice: caller-controlled `X-User-Id` no
   longer authenticates or overrides the validated JWT subject.
2. The generation request trusts browser-supplied job title, employer, and
   description instead of resolving the selected canonical job.
3. The generation, export, and application path is synchronous and non-atomic;
   it has no operation state, idempotency key, cancellation, or safe retry.
4. Generated documents are already stored and linked as
   `DOCUMENTS_GENERATED` before user preview/edit/approval.
5. Resolved in the GW-01 Store consumer slice: file download UUIDs are sent
   only with the Store reader identity and validated owner context.
6. Resolved at the service-contract boundary in the GW-01 Store and Export
   consumer slices: direct Store read/create/activate operations and the
   intervening Export upload/conversion call are owner bound. Fleet E2E
   evidence remains required.
7. Resolved at the service-contract boundary in the GW-01 gateway and Export
   consumer slices: Application Tracker lookups and Export replacement require
   a validated owner plus dedicated service identity. Cross-user fleet evidence
   remains required.
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
