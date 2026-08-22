# Document-generation accounting

## Two deliberately separate models

The customer buys document generations. A durably delivered CV consumes one
generation, a durably delivered cover letter consumes one, and a deliberately
requested regeneration consumes one when that new document is delivered.
Failure before delivery, provider retries, replay of the same operation and a
duplicate delivery consume nothing extra.

Provider resource accounting remains an internal operations concern. The
durable operation ledger retains document type, generation source, model and
pricing versions, provider attempts, automatic retries, input/output token
counts and the provider's admission-time cost estimate. None of those fields
is returned by `GenerationOutputResultResponse` or included in OpenAPI 3.0.0.

## Internal report

Run the read-only report against the Document Generation Gateway PostgreSQL
database with an audited operations identity:

```bash
psql "$DOCUMENT_GENERATION_DATABASE_URL" \
  --file scripts/report-document-generation-accounting.sql
```

It reports successful CVs, successful cover letters, deliberate
regenerations, failures, retries, source/model/pricing version, token usage,
total estimated provider cost and average estimated cost per delivered output.
The estimate is the immutable provider pricing evidence recorded at admission;
it must not be relabelled as an invoice or exposed to a customer.

Payment Service is authoritative for allowance consumed and remaining. Run its
companion report and invariant verifier for delivery-to-consumption matching.
An operations review joins the two ledgers only by the opaque generated
document ID; it must not copy prompt, profile, job or document content.
