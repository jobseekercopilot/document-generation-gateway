# DOCGEN-09 durable generation coordinator

This slice adds the owner-scoped, durable operation boundary for initial CV
and cover-letter generation. It is additive: the legacy browser-payload
`/jobs/{jobId}/generate` route remains available while the Client and BFF move
to the saved-job operation API.

## API

- `POST /api/v1/document-generation/saved-jobs/{savedJobId}/operations`
  requires the validated bearer token and a safe `Idempotency-Key`.
- `GET /api/v1/document-generation/operations/{operationId}` returns only an
  operation owned by the authenticated token subject.
- `POST /api/v1/document-generation/operations/{operationId}/approve` requires
  the exact CV and cover-letter draft IDs created by that operation.
- `DELETE /api/v1/document-generation/operations/{operationId}` cancels only
  while cancellation is deterministic.

The browser supplies no Job content and cannot choose owner or workload
identity headers. Gateway resolves the immutable Saved Job and bounded Profile
snapshot itself and persists their versions and SHA-256 evidence.

## Durable step policy

| Step | Replay policy |
| --- | --- |
| Operation create | Unique by owner/idempotency key and owner/saved job |
| Snapshot lookup and estimate | Safe to repeat |
| Payment reserve/commit/release | Stable operation key or reservation ID |
| Model generation | Enter `GENERATION_IN_PROGRESS` before the call; never automatically repeat an interrupted call |
| Store draft create | Stable per-operation document keys |
| Store approval | Idempotent exact-version transition |
| Export | Stable per-operation/per-document key; producer derives stable DOCX/PDF Store keys |
| Tracker create | Stable per-operation application key |

Each successful side effect is checkpointed in PostgreSQL. A database lease
bounds concurrent workers, while database uniqueness bounds concurrent
requests for the same owner and saved job. Lease expiry permits another worker
to resume replay-safe steps.

## Failure and cancellation semantics

- Deterministic rejection before provider invocation releases an existing
  reservation and records `FAILED`.
- A timeout, disconnect or invalid response after model invocation records
  `GENERATION_OUTCOME_UNKNOWN`. The reservation is retained for explicit
  reconciliation and the model call is not retried.
- Store, Payment, Export and Tracker failures retain their last safe state and
  can be retried with the same downstream keys.
- An interrupted export remains at its `*_EXPORT_IN_PROGRESS` checkpoint and
  safely resumes the same producer and Store operations.
- Cancelling before provider invocation releases the reservation. Cancelling
  at `AWAITING_APPROVAL` preserves already billed drafts as audit evidence and
  creates no application. Cancellation is rejected during ambiguous or
  post-approval work.

`manualActionRequired=true` is returned for an ambiguous model outcome or a
non-replayable recovery failure. No error path silently refunds known model
use, creates an application before explicit approval, or reruns an uncertain
paid call.

## Runtime configuration

Gateway now requires:

```text
DOCUMENT_GENERATION_DATABASE_URL
DOCUMENT_GENERATION_DATABASE_USERNAME
DOCUMENT_GENERATION_DATABASE_PASSWORD
DOCUMENT_GENERATION_GATEWAY_TO_PAYMENT_SERVICE_TOKEN
PAYMENT_SERVICE_URL
```

The Payment token is a distinct workload identity and is accepted only for
owner-scoped reservation create/read/commit/release. Infrastructure must inject
the same secret into Payment Service and Gateway and provide the PostgreSQL
database before this path is enabled in a deployed fleet.

## Current limits

- Store currently exposes `DRAFT` and immutable approved/current behavior, not
  a separate `FINAL` state. This slice uses the exact approved version as the
  Tracker reference.
- Ordinary Export `3.0.0` requires a stable operation key and derives
  per-format Store keys. A timeout or restart resumes the same DOCX/PDF writes
  without duplicating file versions.
- Regeneration is a later operation type. The initial slice permits one bounded
  generation operation per owner and saved job.
- The legacy synchronous route is not removed by this change; Client migration
  and integrated fleet evidence remain required before beta.
