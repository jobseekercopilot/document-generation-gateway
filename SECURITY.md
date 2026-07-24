# Security

Do not disclose vulnerabilities in a public issue.

Use a private GitHub Security Advisory for this repository and include:

- affected commit and component;
- reproduction steps using synthetic data only;
- expected and observed behaviour;
- impact, including cross-user or personal-data exposure;
- suggested remediation if known.

Do not attach credentials, access tokens, real CVs, cover letters, prompts,
model responses, exported documents, or browser sessions. Revoke or rotate any
credential that may have been exposed before sharing redacted evidence.

Browser-provided `X-User-Id`, `X-Service-Token` and
`X-Application-Owner` values have no authority at this gateway. User identity
comes only from a validated platform access token. Downstream service
identities are injected from runtime configuration and must never be copied
from incoming requests.

Document Store and Document Export ownership enforcement remains incomplete,
so this service is not approved for production or real-user data. See
[`docs/IDENTITY_BOUNDARY.md`](docs/IDENTITY_BOUNDARY.md).
