# Migration provenance

- Migration date: 2026-07-23
- Source workspace commit: `94b4ba5ceaee1e9745c24693078f094d6abe4c6f`
- Source path: `document-generation-gateway/`
- Source state: untracked in the root repository; no service-specific Git
  history was available to import safely.
- Root branches/tags bundle SHA-256:
  `417732dd04dfacb906c5b9d169a390d106246b5cbb47ee64c6242d41d95ccac6`
- Sanitised source archive SHA-256:
  `7162f66af43a2810546337853e36cbab112fc53fb5a65ac8e241cbe1c6f596c0`

The root repository and its branches were not modified. Build output, local
client JARs, environment files, databases, logs, recordings, exported
documents, and model output were not imported. The migration-time HMAC filter
has since been replaced by the platform RS256/JWKS access-token boundary. No
signing or service-identity secret is source controlled.
