# Contributing

This is a private proprietary repository.

## Workflow

1. Start from the latest `develop`.
2. Create one focused `feature/<issue-number>-<description>` branch.
3. Address one issue per branch and pull request.
4. Open the pull request into `develop`.
5. Run `mvn -B clean verify` and document any additional validation.
6. Do not merge failing checks or push pre-release work to `main`.

Never commit credentials, `.env` files, personal data, prompts or responses
containing real user data, generated CVs or cover letters, exported documents,
runtime databases, recordings, build output, or generated binary clients.

Security and privacy fixes require negative tests for cross-user access and
checks that logs do not contain document content or model input/output.
