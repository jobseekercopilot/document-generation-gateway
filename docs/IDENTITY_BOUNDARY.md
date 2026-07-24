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
| CV and Cover Letter | Runtime `CV_COVER_LETTER_GATEWAY_TOKEN` plus exactly one `X-Document-Owner` set to validated JWT `sub` | Enforced by CV/Cover Letter 2.0.0 and the gateway |
| Document Export generation/replacement | Runtime `DOCUMENT_EXPORT_GATEWAY_TOKEN` plus exactly one `X-Document-Owner` set to validated JWT `sub` | Enforced by Export 2.0.0 and the gateway |
| Document Store download/read | Runtime `DOCUMENT_STORE_READER_TOKEN` plus exactly one `X-Document-Owner` set to validated JWT `sub` | Enforced by Store 1.1.0 and the gateway |
| Document Store create/activate | Runtime `DOCUMENT_STORE_PRODUCER_TOKEN` plus exactly one `X-Document-Owner` set to validated JWT `sub` | Enforced by Store 1.1.0 and the gateway |

The Authentication, Application Tracker, CV/Cover Letter, Document Export and
two Document Store tokens are pairwise distinct, contain at least 32 bytes, and are
injected only from runtime secret configuration. Startup fails closed when any
is absent, short, or shared. Token values must not be placed in Compose files,
browser code, logs, command lines, issue text, metrics or build arguments.

## Residual boundary and rollout

This change prevents caller-selected gateway identity and secures the currently
available User Profile, Authentication, Application Tracker, CV/Cover Letter,
direct Document Store and Document Export boundaries. Raw file UUID downloads,
generation, generated exports and replacement uploads are owner scoped. GW-01
remains open and beta-blocking until credentials are injected by Infrastructure
and cross-user generation, upload, replacement and download tests pass end to
end.
