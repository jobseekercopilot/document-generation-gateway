# Producer contract governance

Document Generation Gateway consumes eight live cross-service boundaries. It
resolves the User Profile, legacy CV/Cover Letter and Document Export Java
clients from their producers' immutable private Maven packages. It no longer
generates Java clients inside the consumer build. Authentication, Job,
Payment, Document Store, Application Tracker and the new CV/Cover Letter
draft flow use focused handwritten HTTP boundaries, so their consumed
operations are pinned and checked without generating unused clients. The
unused generated client dependencies and API beans are removed rather than
replaced with new dead output.

## Current pins

| Producer | Revision | Contract | Version | SHA-256 | Use |
| --- | --- | --- | --- | --- | --- |
| `jobseekercopilot/authentication-service` | `2964aeb07b9861cce555d28cc58c6b9fab1f6107` | `contracts/openapi.json` | `1.0.0` | `ce7f707b921a16fb8e53b580032bac4542334ed47f8f07c474e63ba2ecc4c812` | Handwritten `/api/auth/me` adapter compatibility |
| `jobseekercopilot/user-profile-service` | `806ed6064d10b2de9171b14bd252477c4646de35` | `api/openapi.json` | `2.1.0` | `1770e3aed76aa69f16015b496947e9ed2d28fb93f48e21fd4abcb03ec38d680d` | Published Java client `com.jobseekercopilot.clients:user-profile-service-client:2.1.0-rev.806ed6064d10` |
| `jobseekercopilot/cv-cover-letter-service` | `2e79ca3963f49f15ecdfa15b7d288da78dfdf70a` | `contracts/openapi.json` | `4.1.0` | `ae08510ff3cd97d2d46922f050a56fd4d109f726bf687682fdf8f6ad23278f6d` | Published Java client `com.jobseekercopilot.clients:cv-cover-letter-service-client:4.1.0-rev.2e79ca3963f4`; selected-output generation omits the unselected document while retaining exact claim-ledger identity, digest and bounded claims |
| `jobseekercopilot/job-service` | `badf3f061732a0bc662722227ee19f877dd463da` | `api/openapi.yaml` | `2.0.0` | `6465ccfab96a5df67e3bb16a06c3edc4d2a76735b2d789a2237b264636127506` | Saved-job lookup supplies the canonical immutable snapshot and digest |
| `jobseekercopilot/payment-service` | `0430cd09fd390a09d5445672504560ffde64cbe4` | `contracts/openapi.json` | `3.0.0` | `08312957171b34df832b5b3e62ffba93d68007981ac7c284e8b0bff7de22295a` | Dedicated Gateway identity may use only owner-scoped reservation lifecycle routes |
| `jobseekercopilot/document-export-service` | `b71014fe72d5b3660a95e55facd3d627078d892f` | `contracts/openapi.json` | `3.0.0` | `17d37926cc6c9dedacb526e018577cb3c1aaf976fdcda24a588d541f9ec4f042` | Handwritten ordinary export and replacement adapters use stable replay keys; the old generated client remains only for the legacy route |
| `jobseekercopilot/document-store-service` | `e5550c1bbb916f241b89e7cba4635b3b5b25263c` | `contracts/openapi.json` | `3.3.0` | `e521181e393d87919339d56a40d1fad2ae53d7d6dda2959b90508f405d5968af` | Family history includes scrubbed purge tombstones, exact associations, and the additive account export contract |
| `jobseekercopilot/application-tracker-service` | `89d231e5c15b9bd4ce3b3d16064439488530b5f0` | `contracts/openapi.json` | `4.7.0` | `3d106cd0579721d68d683a980f7cdf7dd44b7ba2ce63cf21df158bd32db6fb86` | Saved applications may be established before generation and now emit the content-free `APPLICATION_SAVED` lifecycle event; existing create-event semantics remain compatible |

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
- Job Service owner-scoped saved-job lookup, immutable snapshot and content
  digest;
- CV/Cover Letter side-effect-free estimate and owner-scoped draft generation
  with the coordinator operation ID and actual token usage;
- Payment Service owner-scoped reserve/read/commit/release lifecycle through
  the Gateway's least-privilege service identity;
- Document Export replay-safe DOCX/PDF export and replacement upload;
- Document Store get/create/approve/download operations, family continuity,
  idempotency, service identity and owner-context parameters;
- Application Tracker owner-scoped reads and durable replacement
  begin/register/complete/recovery operations, including its service-token
  security scheme.

Compatibility does not by itself prove runtime authorisation. The GW-01
gateway slice now supplies the validated request bearer to User Profile,
supplies bearer plus the runtime Authentication service identity to
`/api/auth/me`, and supplies the runtime Application Tracker producer identity
plus the validated owner subject to owner-scoped Tracker reads and document
replacement workflow operations. Negative tests reject missing, malformed, expired, forged,
wrong-issuer, wrong-audience, wrong-purpose and subjectless access tokens.
Direct Store reads/downloads use its runtime reader identity; creates and
approval use its producer identity. Both bind exactly one owner header to the
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
