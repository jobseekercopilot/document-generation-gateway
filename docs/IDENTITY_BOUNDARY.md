# Document Generation identity boundary

## Browser identity

Every `/api/v1/document-generation/**` operation requires an Authentication
Service access token. The gateway accepts only RS256 tokens resolved from the
configured JWKS endpoint and validates issuer, audience, expiry, nonblank
`sub`, and `token_type=access`.

The authenticated owner is always the validated JWT `sub`. Browser-provided
`X-User-Id`, `X-Service-Token` and `X-Application-Owner` values are ignored as
authority and are never copied into trusted downstream identity headers.
Missing, malformed, expired, forged, wrong-issuer, wrong-audience,
wrong-purpose and subjectless tokens receive the same redacted
`401 AUTHENTICATION_REQUIRED` response.

## Downstream identity

| Boundary | Identity sent by the gateway | Current status |
| --- | --- | --- |
| User Profile | Validated user Bearer token, resolved per request | Enforced by the producer |
| Authentication `/api/auth/me` | Validated user Bearer plus runtime `AUTH_SERVICE_TOKEN` | Enforced by the producer |
| Application Tracker owner operations | Runtime `APPLICATION_TRACKER_PRODUCER_TOKEN` plus `X-Application-Owner` set to validated JWT `sub` | Enforced by the producer |
| CV and Cover Letter | `X-User-Id` set only from validated JWT `sub` | Producer service identity remains CVCL-02 |
| Document Export | Existing generated/raw operations | Producer service identity and owner enforcement remain dependency work |
| Document Store download/read | Runtime `DOCUMENT_STORE_READER_TOKEN` plus exactly one `X-Document-Owner` set to validated JWT `sub` | Enforced by Store 1.1.0 and the gateway |
| Document Store create/activate | Runtime `DOCUMENT_STORE_PRODUCER_TOKEN` plus exactly one `X-Document-Owner` set to validated JWT `sub` | Enforced by Store 1.1.0 and the gateway |

The Authentication, Application Tracker and two Document Store tokens are
pairwise distinct, contain at least 32 bytes, and are injected only from
runtime secret configuration. Startup fails closed when any is absent, short,
or shared. Token values must not be placed in Compose files, browser code,
logs, command lines, issue text, metrics or build arguments.

## Residual boundary and rollout

This change prevents caller-selected gateway identity and secures the currently
available User Profile, Authentication, Application Tracker and direct Document
Store boundaries. Raw file UUID downloads and direct Store replacement calls
are now owner scoped, but the gateway still reaches CV/Cover Letter and
Document Export boundaries that do not yet enforce the same trusted identity.
GW-01 therefore remains open and beta-blocking until those producer
dependencies are implemented, credentials are injected by Infrastructure, and
cross-user generation, upload, replacement and download tests pass end to end.
