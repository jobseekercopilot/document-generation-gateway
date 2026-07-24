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

Direct Document Store calls use distinct reader/producer identities and
`X-Document-Owner` derived only from the validated token subject. Document
Export and CV/Cover Letter ownership enforcement, credential deployment and
integrated cross-user evidence remain incomplete, so this service is not
approved for production or real-user data. See
[`docs/IDENTITY_BOUNDARY.md`](docs/IDENTITY_BOUNDARY.md).
