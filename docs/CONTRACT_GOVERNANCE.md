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
| `jobseekercopilot/cv-cover-letter-service` | `68b4cf9d3f2395abd642180a204db3a67d9ae80e` | `contracts/openapi.json` | `1.0.0` | `c493db389a3255c29cc250d14ede2e8a1a03c7efe22e340ccc009584ab4e225b` | Published Java client `com.jobseekercopilot.clients:cv-cover-letter-service-client:1.0.0-rev.68b4cf9d3f23` |
| `jobseekercopilot/document-export-service` | `aa7f34693d81e55686c90441b105a195b614a545` | `contracts/openapi.json` | `1.0.0` | `f33fcb0994db6689b001219b651a55d4d499cb94af32d3defab87f3fc56c3012` | Published Java client `com.jobseekercopilot.clients:document-export-service-client:1.0.0-rev.aa7f34693d81` and raw upload compatibility |
| `jobseekercopilot/document-store-service` | `fedcdbdec63795269c4e4c4f43fc32f38c6327b1` | `contracts/openapi.json` | `1.0.0` | `410ab1a7a2e8a5a5ad374443ec834f6aef7f778f6936b3ef90c33f8e580cdbd9` | Handwritten adapter compatibility |
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
- Document Store get/create/activate replacement operations;
- Application Tracker owner-scoped reads and document-reference update,
  including its service-token security scheme.

Compatibility does not prove runtime authorisation. The User Profile contract
now has a no-argument `getMyProfile()` operation protected by bearer security,
while the gateway client configuration supplies no per-request token.
Application Tracker likewise requires bearer or service-token identity and
owner context that the raw gateway calls do not yet send. GW-01 owns both
direct beta blockers. Authentication requires bearer and service identity
together; the handwritten adapter currently forwards only the bearer token, so
GW-01 also owns that runtime propagation blocker. Document Store producer
authorisation remains STORE-01.

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
