# Error-taxonomy vectors

Language-agnostic vectors pinning the **error taxonomy** every SDK exposes. One dataset, asserted by every SDK's
conformance suite through its **real client library + a mock transport**, so a divergence in how any SDK
classifies a failure is caught mechanically.

The taxonomy has one catchable root and three kinds beneath it, expressed in each language's idiom:

| taxonomy   | meaning                                                     | example types |
|------------|-------------------------------------------------------------|---------------|
| `api`      | an HTTP **error response** was received (status ≥ 400, or a 2xx whose body could not be parsed — the latter carries the real 2xx status of the unusable exchange) | TS `CountersApiError`, Go `*APIError`, Java `CountersApiException` |
| `transport`| **no HTTP response** was ever obtained (network error / retries exhausted) | TS `CountersTransportError`, Go `*TransportError`, Java `CountersTransportException` |
| `validation`| rejected **client-side** — a bad input before any request, or a response payload the SDK cannot faithfully represent | `*ValidationError` / `CountersValidationException` |

## Loader contract (`cases.json`)

Four arrays, each an object with a `name`, an input, and an `expect` block:

- **`api[]`** — `response: { status, body }` (`body` is the parsed problem+json object, or `null` for an
  empty body). Drive one request through the SDK with **retries disabled** and a mock transport that
  returns exactly this `status`/`body`. Assert the thrown/returned error is the SDK's **api** type, that
  its status equals `expect.status`, and — when `expect.title` is present — that its title/message carries it.
- **`transport[]`** — `transport: "network-error"`. Drive one request with a mock transport that raises a
  connection error (or otherwise never yields a response) on every attempt. Assert the SDK's **transport**
  type, and assert it is **not** the api type and carries no HTTP status (the status-0 regression this pins).
- **`validation[]`** — `validate: { key }` or `validate: { amount }`. Feed the value into the SDK's
  key/amount validation (e.g. `client.counter(key)` / the amount coercion) and assert the **validation** type.
- **`batch[]`** — `response: { status: 200, body }` where `body` is a `/batch` `BatchResponse` whose
  `results` include at least one per-op `"error"`. Drive **one
  buffered write + an explicit flush** (or the SDK's confirmed single-op batch path) through the real
  client with a mock transport returning exactly this body. Assert by `expect.taxonomy`:
  - `api` — the SDK's **api** type, status equal to `expect.status` (the per-op problem's status), and
    the title/message carrying `expect.title`.
  - `validation` — the SDK's **validation** type (message-carrying SDKs include the per-op context).
    Assert it is **not** the
    api type and carries no HTTP status — an api error with status 0/nil/undefined here is the
    regression this pins.

Every `sdk-*-ci.yml` re-triggers on `conformance/**`, so adding a case here fans out to all suites with no
code change. The server side of the `batch[]` rule — per-op problems always carry `status` — is pinned by
the server suite against `Problems.kt` (`toProblemDto`).
