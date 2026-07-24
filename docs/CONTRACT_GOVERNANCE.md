# Producer contract governance

Document Generation Gateway consumes six live cross-service boundaries. It
resolves the User Profile, CV/Cover Letter and Document Export Java clients
from their producers' immutable private Maven packages. It no longer generates
Java clients inside the consumer build. Authentication, Document Store and
Application Tracker use handwritten `RestTemplate` boundaries, so their
consumed operations are pinned and checked without generating unused clients.
The unused generated client dependencies and API beans are removed rather than
replaced with new dead output.

## Current pins

| Producer | Revision | Contract | Version | SHA-256 | Use |
| --- | --- | --- | --- | --- | --- |
| `jobseekercopilot/authentication-service` | `2964aeb07b9861cce555d28cc58c6b9fab1f6107` | `contracts/openapi.json` | `1.0.0` | `ce7f707b921a16fb8e53b580032bac4542334ed47f8f07c474e63ba2ecc4c812` | Handwritten `/api/auth/me` adapter compatibility |
| `jobseekercopilot/user-profile-service` | `86c8510ed319a059b991e6f9f1e43b0e101c5d1f` | `api/openapi.json` | `1.0.0` | `ffaaa16a169ab11d864f82440be9fcc7d5df2d4f2d63a3525d40bda497ea6598` | Published Java client `com.jobseekercopilot.clients:user-profile-service-client:1.0.0-rev.86c8510ed319` |
| `jobseekercopilot/cv-cover-letter-service` | `87fc2393309ad3007cba6ac27aa618fc3cc81aa9` | `contracts/openapi.json` | `2.0.0` | `8583f844b297bc32472e1fbf0b4bc273972477cc81cb0a25eba6e2ac5a048d95` | Published Java client `com.jobseekercopilot.clients:cv-cover-letter-service-client:2.0.0-rev.87fc2393309a`; generation requires service identity plus owner |
| `jobseekercopilot/document-export-service` | `a35fff34f86b77457df4b9e324000a32819d5aba` | `contracts/openapi.json` | `2.0.0` | `e696b76efc05149778d1a31df684b6ba5687120396fb6e000ce4f888346f5072` | Published Java client `com.jobseekercopilot.clients:document-export-service-client:2.0.0-rev.a35fff34f86b`; generated export and raw upload calls require service identity plus owner |
| `jobseekercopilot/document-store-service` | `b696fe81e9b900e0749e185f595ff4c98c24119d` | `contracts/openapi.json` | `1.1.0` | `3d0595c83cc66d9037e08af6a4b087c115c9a5d99ec71491f1aa5fc3afffd6ba` | Owner-scoped handwritten read/create/activate/download adapter compatibility |
| `jobseekercopilot/application-tracker-service` | `d9e6bc9fcbe4ef665334c58672c8062b1e4796aa` | `contracts/openapi.json` | `1.1.0` | `549cebba300c2caf3403b9de01d3c34de84464a280a02183751e8a9583ad982a` | Handwritten adapter compatibility |

The `.SOURCE` files record the reviewed producer revisions and `SHA256SUMS`
protects the exact contract bytes used for compatibility policy. Generated
sources and binaries remain producer-side disposable build output.

GitHub Maven packages are repository-scoped. Cross-repository CI uses a
dedicated classic `JSC_PACKAGE_READ_TOKEN` secret with `read:packages` and
private-repository access. Maven reads it through the reviewed settings
template. Container builds pass the template and an ephemeral token file as
BuildKit secrets; the credential is never a build argument, image layer,
tracked file or artifact. Docker Buildx is therefore a required build
prerequisite; the legacy Docker builder is intentionally unsupported.

## Compatibility boundaries

The policy checks the exact operations and fields currently consumed:

- Authentication account lookup, contact fields and its combined bearer and
  service-identity requirement;
- authenticated User Profile retrieval and profile fields;
- CV/Cover Letter generation request, job and generated document IDs;
- Document Export DOCX/PDF export and replacement upload;
- Document Store get/create/activate/download operations, service identity and
  owner-context parameters;
- Application Tracker owner-scoped reads and document-reference update,
  including its service-token security scheme.

Compatibility does not by itself prove runtime authorisation. The GW-01
gateway slice now supplies the validated request bearer to User Profile,
supplies bearer plus the runtime Authentication service identity to
`/api/auth/me`, and supplies the runtime Application Tracker producer identity
plus the validated owner subject to owner-scoped Tracker reads and document
reference updates. Negative tests reject missing, malformed, expired, forged,
wrong-issuer, wrong-audience, wrong-purpose and subjectless access tokens.
Direct Store reads/downloads use its runtime reader identity; creates and
activation use its producer identity. Both bind exactly one owner header to the
validated token subject. Document Export generation and replacement use its
dedicated runtime identity and bind exactly one owner header to the validated
token subject. CV/Cover Letter generation now uses its dedicated runtime
identity and binds exactly one owner header to that same subject. GW-01 stays
open until Infrastructure injects these credentials and integrated cross-user
paths pass.

## Updating a pin

1. Merge and verify the producer change.
2. Record its exact merged `develop` revision and producer-owned contract.
3. Review the API diff for compatibility, identity, privacy and transaction
   impact.
4. Copy the exact producer artifact into `src/main/openapi`.
5. Update its `.SOURCE` file and `SHA256SUMS`.
6. Update policy assertions only when the consumer change is intentional.
7. Run:

   ```bash
   ./scripts/test-contract-policy.sh
   ./scripts/verify-contracts.sh
   JSC_PACKAGE_READ_TOKEN=... mvn -B --no-transfer-progress \
     -s .mvn/github-packages-settings.xml clean verify
   JSC_PACKAGE_READ_TOKEN=... ./scripts/build-container.sh \
     local/document-generation-gateway
   ```

8. Merge only after pull-request and post-merge `develop` CI pass.

If a producer change is incompatible, retain the previous reviewed pin until
the consumer is ready. Rollback is a normal revert to the previous contract,
source metadata and checksum as one change; never substitute an untracked
generated JAR.
