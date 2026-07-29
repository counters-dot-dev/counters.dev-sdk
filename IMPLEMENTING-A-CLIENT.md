# Implementing a counters.dev client

## 1. How to use this guide

This guide is everything you need to implement a **correct** client wrapper for the counters.dev API
in any language. It is the human companion to the two machine-readable artefacts in this repository:

- [`openapi/openapi.yaml`](./openapi/openapi.yaml) — the **contract of record**. Authoritative for
  *shape*: every path, operation, parameter, schema, status code, and header. Every fact in this
  guide was verified against it; where prose and spec could ever disagree, the spec wins.
- [`conformance/`](./conformance) — the **behaviour vectors**. Authoritative for *behaviour*: key
  and amount validation, arbitrary-precision arithmetic, query encoding, response parsing, the error
  taxonomy, and full HTTP interactions, pinned as language-agnostic JSON you replay in your own test
  suite (see §13).

This guide explains the contract as a coherent whole and calls out the non-obvious rules a naive
client gets wrong. Field-level detail is deliberately deferred to `openapi.yaml`; read the three
together.

First-party SDKs exist for TypeScript, Java, and Go in this repository, but they are not the
definition of correctness: no rule in this guide is sourced from their code, and yours should not be
either. *Correct* means "matches the spec and the vectors". If anything here disagrees with
`openapi.yaml` or the vectors, this guide is wrong — please open an issue.

## 2. Base URL and versioning

All machine-API operations live under one base URL:

```
https://api.counters.dev/v1
```

The paths in `openapi.yaml` are written relative to that `/v1` base; this guide writes them in full
(`/v1/counters/...`). A few surfaces live at an origin root instead of under `/v1`: the key-less
demo endpoints at `https://api.counters.dev/public/...`, the dashboard hub endpoint
`POST /dashboard/plane-token` at the API origin root, and the plane-local `/dashboard-read/*`
endpoints at each data plane's origin. None of those are part of the client contract — see §12.

The spec's `info.version` is **0.7.0**. The API evolves **additively within a major version** — new
response fields, new operations, and new optional parameters are minor changes; breaking changes
require a major-version bump. The practical consequence is a hard rule for your parser: **tolerate
unknown response fields**. Ignore anything you do not recognise rather than failing deserialisation.
The spec relies on exactly this tolerance itself: the quota-`403` extension members (§8) are
explicitly optional so older problem consumers keep parsing those bodies.

Make the base URL a configuration option of your client, so the same code can be pointed at a
staging or local deployment.

## 3. Authentication

Every machine-API call carries an HTTP Bearer credential:

```
Authorization: Bearer <credential>
```

There are two credential kinds:

- **Organization API key (full access).** May call every operation in this guide. All data is scoped
  to the key's organization — a client never names an organization; the key implies it.
- **Publishable token (`pk_` prefix).** Read-only and scoped to specific counters. The spec grants
  publishable bearers exactly three reads: the counter value (`GET /v1/counters/{key}/value`), the
  leaderboard (`GET /v1/counters/{key}/leaderboard` — the embeddable public leaderboard), and a
  member snapshot (`GET /v1/counters/{key}/members/{member}`). Value and leaderboard responses to
  publishable bearers carry CORS and cache headers so they can be called straight from a browser.
  Everything else — every write, listing counters, usage, batch, and the derived reads — requires
  full access and is answered `403 Forbidden`. The spec does not state a publishable rule for the
  series reads; treat them as full-key-only unless the contract says otherwise.

Missing or malformed credentials are `401 Unauthorized` on every operation.

Keep the organization key on servers only. The publishable token is the browser-safe kind: it is
meant to be embedded in public client-side code, and its reduced scope is enforced server-side. A
wrapper may expose a correspondingly reduced surface for publishable tokens, but the enforcement
point is the server's `403`, not your code.

## 4. Values are digit strings — the headline rule

Every counter magnitude on the wire is a JSON **string** of decimal digits, never a JSON number.
Three schema patterns cover all of them:

| Schema | Pattern | Meaning |
|---|---|---|
| `Amount` | `^[0-9]+$` | request magnitude — non-negative integer, arbitrary precision |
| `Value` | `^-?[0-9]+$` | counter/member value — signed integer, arbitrary precision |
| `DecimalValue` | `^-?[0-9]+(\.[0-9]+)?$`, nullable | derived-counter value — signed decimal, or JSON `null` |

The reason is the product's headline guarantee: a JSON number is an IEEE-754 double and silently
loses precision above 2^53, and counters.dev counters do not lose precision — so the wire format
cannot carry them as numbers. Therefore:

- A client **MUST** carry amounts and values end to end in an arbitrary-precision integer/decimal
  type, or as the raw string — through request serialisation and response parsing alike.
- A client **MUST NOT** round-trip a value through a native float or a fixed-width integer
  (`int64`, `u64`, `double`, …). A client that parses `"18446744073709551616"` into a `u64` has
  already failed; `conformance/bignum.json` exists to prove yours does not.

Two adjacent string-typed numbers not to trip over:

- A member's `percentile` is a decimal string at scale 2 (`^-?[0-9]+\.[0-9]{2}$`, e.g. `"83.33"`;
  the leader and a sole member both read `"100.00"`).
- A derived `value` of JSON `null` is **data, not an error** — it means the expression divided by
  zero, and a `reason` member accompanies it. The same holds per bucket in a derived series.

## 5. Operation catalogue

The full `/v1` operation set, exactly as in the spec. Success is `200` with the response schema
shown, except `deleteCounter`, which returns an empty `204`. Field-level detail for every schema is
in `openapi.yaml`. Every write is idempotent-capable: standalone writes take an `Idempotency-Key`
header (§6); inside a batch each operation carries its own `idempotencyKey` (§7).

| Operation | Method and path | Request body | Response body | Notable statuses |
|---|---|---|---|---|
| `listCounters` | `GET /v1/counters` | — | `CounterPage` | 401, 429 |
| `getCounter` | `GET /v1/counters/{counterKey}` | — | `Counter` | 401, 404 |
| `addToCounter` | `POST /v1/counters/{counterKey}/add` | `AmountRequest` | `Counter` | 400, 401, 403, 409, 429 |
| `subtractFromCounter` | `POST /v1/counters/{counterKey}/subtract` | `AmountRequest` | `Counter` | 400, 401, 403, 409, 429 |
| `clearCounter` | `POST /v1/counters/{counterKey}/clear` | — | `Counter` | 401, 403, 404, 409 |
| `deleteCounter` | `DELETE /v1/counters/{counterKey}` | — | none (204) | 401, 403, 404, 409 |
| `getCounterValue` | `GET /v1/counters/{counterKey}/value` | — | `ValueResponse` | 401, 404 |
| `getCounterSeries` | `GET /v1/counters/{counterKey}/series` | — (query) | `SeriesResponse` \| `MemberSeriesResponse` \| `MemberGroupSeriesResponse` | 400, 401, 403 |
| `getCounterLeaderboard` | `GET /v1/counters/{counterKey}/leaderboard` | — (query) | `Leaderboard` \| `WindowLeaderboard` | 400, 401, 404 |
| `getMember` | `GET /v1/counters/{counterKey}/members/{member}` | — (query) | `MemberSnapshot` | 400, 401, 404 |
| `addToMember` | `POST /v1/counters/{counterKey}/members/{member}/add` | `MemberAmountRequest` | `MemberValue` | 400, 401, 403, 409, 429 |
| `subtractFromMember` | `POST /v1/counters/{counterKey}/members/{member}/subtract` | `MemberAmountRequest` | `MemberValue` | 400, 401, 403, 409, 429 |
| `submitMember` | `POST /v1/counters/{counterKey}/members/{member}/submit` | `SubmitRequest` | `MemberValue` | 400, 401, 403, 409, 429 |
| `removeMember` | `DELETE /v1/counters/{counterKey}/members/{member}` | — | `MemberRemoved` | 401, 403, 404, 409 |
| `getUsage` | `GET /v1/usage` | — | `Usage` | 401, 403, 429 |
| `batchOperations` | `POST /v1/batch` | `BatchRequest` | `BatchResponse` | 400, 401, 429 |
| `getDerivedValue` | `GET /v1/derived/{derivedKey}/value` | — | `DerivedValueResponse` | 401, 403, 404, 429 |
| `getDerivedSeries` | `GET /v1/derived/{derivedKey}/series` | — (query) | `DerivedSeriesResponse` | 400, 401, 403, 404, 429 |

What the status columns mean: `400` is a malformed request; `401` is missing/invalid credentials;
`403` on a write is a plan/quota limit (§8), on the series reads it is plan gating of the requested
granularity, and on `getUsage`/derived reads it means the credential lacks full access; `404` is an
unknown (or deleted) counter/member/derived key; `409` is an idempotency-key collision (§6); `429`
is rate limiting with `Retry-After` (§9).

### Counter lifecycle

Counter keys match `^[A-Za-z0-9._:-]+$` and are 1–200 characters; derived keys share the same rule
in a separate namespace. Validate keys client-side — a bad key is a local validation error (§8),
not a request. `addToCounter` creates the counter if absent (subject to the plan's counter limit, a
quota `403`); `subtractFromCounter` may drive it negative. `clearCounter` resets the value to zero
by starting a new **epoch** — a season: the returned counter reads value `"0"` with an incremented
`epoch`, historical series are retained, and past epochs stay readable via the `epoch` query
parameter on the leaderboard and member reads. `deleteCounter` tombstones the counter (`204`, then
`404`); its events are purged asynchronously per retention.

### Value and series reads

`getCounterValue` reads the current-epoch value. `getCounterSeries` returns the per-bucket **delta**
of the counter over `[from, to)` — the change in each bucket, not a running total (the `mode`
parameter accepts only `delta`). Parameters: `from` and `to` (RFC 3339, required), `bucket`
(required; one of `1m`, `5m`, `1h`, `1d`, `1w`, `1mo`), `tz` (IANA timezone for calendar bucket
boundaries, default `UTC`), and `gapfill` (default `false`). Empty buckets are omitted unless
`gapfill=true`; **treat a missing bucket as zero**. Because `false` is the default, omit `gapfill`
entirely when false — an explicit `gapfill=false` is treated identically to omission. Granularity is
**plan-gated**: finer buckets require higher plans, and an over-entitled `bucket` is `403`; lookback
is bounded by the storage lifecycle, not read-gated. Writes can be back-dated into the series with
`occurredAt` — see §10.

Two dimensional variants share the same endpoint: `member=<key>` returns one member's per-bucket
series (`MemberSeriesResponse`), and `groupBy=member` returns a series per member with data in range
(`MemberGroupSeriesResponse`). They are mutually exclusive, require the counter to have member
series enabled, and require `bucket` at `1h` or coarser. On a score board (`min`/`max`/`latest`)
each point is the bucket-best/latest value, points are **sparse** — a gap is "no submission", not
zero — and `gapfill=true` is rejected (`400`); the response's `mode` field tells you how to read
each bucket's value (`delta` on a sum board, else the board mode).

### Leaderboards and members

A leaderboard is the same counter with per-member sub-values. The board **mode** — `sum` (accumulate
deltas) or the score modes `latest`/`min`/`max` (submit scores; keep latest/best) — is fixed by the
first member write and is then immutable: member add/subtract configure a `sum` board on first use,
and `submitMember` requires `mode` on the first submit to an unconfigured board. A worse-than-
standing submit to a `min`/`max` board is still a successful, metered `200` — it returns the
standing value with `memberAccepted: false`. Member keys match `^[A-Za-z0-9._:@|-]+$` and are
1–256 characters; an optional `metadata` string (at most **1024 UTF-8 bytes**, counted in bytes, not
characters) rides accepted member values and is returned verbatim.

`getCounterLeaderboard` pages ranked entries with `limit` (1–1000, default 100) and `offset`
(0–100000, default 0). `order` defaults to `desc`, except `min` boards which default to `asc`
(fastest-first); an explicit `order` overrides. `total` (the group total) is present only on `sum`
boards. With `window=` (`1h`, `6h`, `12h`, `1d`, `7d`, `30d`) the response is a `WindowLeaderboard`:
members ranked by **activity over the trailing window** (window-sum on a sum board, window-best/
latest on a score board) rather than all-time standing — members with no window activity are absent.
Windowed boards require member series enabled, ignore `epoch`, and report the effective summed range
as `effectiveStart` (floored to the 1h rollup boundary, so possibly earlier than `now − window`) and
`effectiveEnd`; `total` is `null` on score boards. `getMember` returns one member's value,
competition **rank** (ties share; the next rank skips: 1, 2, 2, 4), `percentile`, and board state.
`removeMember` removes the member from the current epoch; on `sum` boards a non-zero value is
compensated into the group total.

### Usage and quota

`getUsage` returns machine-readable quota state for the calling organization: the UTC `month`,
`ops.used`/`ops.quota`/`ops.resetsAt` (write ops this month; `quota` is `null` for unlimited plans,
`resetsAt` is present regardless), `counters.used`/`counters.max`, and the plan's `limits`
(`rateLimitRps`, `maxCounters`, `monthlyOpsQuota`). It is for **periodic polling** (e.g. once a
minute), not per-write interrogation — the hot write path deliberately exposes no per-request usage
headers.

### Derived counters

A derived counter is a stored, named, **read-only** expression over the organization's counters
(e.g. `conversion = c('signups') / c('visits')`), evaluated at read time. Definitions are managed
out-of-band (on the dashboard); the machine API only evaluates them by key, and requires a
full-access key — no publishable tokens. Referenced counters need not exist: a missing or deleted
counter reads as `"0"`, made visible in the `inputs` map. Arithmetic is exact-decimal (DECIMAL128),
rounded HALF_UP to the definition's `scale`. Division by zero anywhere in the expression makes the
whole `value` `null` with a `reason`. `getDerivedSeries` evaluates the expression per bucket over
`[from, to)` with the same `bucket`/`tz`/plan-gating rules as counter series; each referenced
counter's series is gap-filled to zeros, so the derived series is always **dense**, and a per-bucket
division by zero yields `v: null` for that bucket only.

## 6. Idempotency

Every write — `add`, `subtract`, `clear`, `delete`, and the member operations — accepts an
`Idempotency-Key` header (at most **255 characters**). The header is optional in the spec, but a
client **should send a fresh key on every write**: it is what makes retries safe.

The semantics are at-least-once delivery plus deduplication — **effectively-once**: retrying the
same operation with the same key within the window replays the original result (the same value and
epoch, or the original `204` for a delete) instead of re-running the write.

- The **guaranteed deduplication window is six hours**. Keys older than six hours are eligible for
  pruning, so reusing one later may execute a new operation.
- A key is **scoped to the operation that first used it**. Reusing a live key for a *different*
  operation (e.g. an `add` key replayed on a `clear`) is `409 Conflict` — not a wrong result. The
  remedy is a fresh key (or the operation the key belongs to), never the same key twice on different
  operations.
- Each organization has a plan-derived **live-key cap**, defaulted to 110% of the maximum writes its
  enforced rate limit permits during six hours; traffic within that rate limit cannot exhaust the
  cap. If it is exhausted, a standalone write returns `403 Forbidden`, and a batch reports an
  `error` result with status `403` for that operation. The response carries **no `Retry-After`** —
  the service does not promise an exact recovery time — so it **must not be auto-retried**; retry
  only after older keys have left the six-hour window.

## 7. Batch

`POST /v1/batch` applies up to 1000 operations in one call, and it is the **recommended primary
write path**: quotas meter operations, not magnitude — an `add` of any size is one op, and each op
in a batch still counts as one — so accumulating client-side and flushing the coalesced total gives
you effectively unlimited increments.

The request body is a `BatchRequest`: an `operations` array of 1–1000 `Operation` objects. Each
operation carries `counterKey` and `op` (one of `add`, `subtract`, `clear`, `delete`, `submit`),
plus optional `amount` (required for add/subtract, ignored for clear/delete), `member` (turns an
add/subtract into a member delta write and a delete into a member removal), `value` (required, with
`member`, for `submit`, which targets an already-configured score board), `metadata`, `occurredAt`,
and its own `idempotencyKey`. There is **no batch-level idempotency key** — each operation dedups
independently — so a batch is safe to retry wholesale.

HTTP `200` means the batch was **accepted**, not that every operation succeeded. The client must
inspect each entry of `results`: its `status` is `applied`, `deduplicated` (a retry replay), or
`error`, and an `error` entry carries an embedded problem object that always has its own `status`
— including the per-operation `403` of the idempotency-key cap (§6). Member operations report
`memberValue` and, for submits, `memberAccepted`. Treat a per-operation error exactly like the
standalone status it carries.

## 8. Error model

Error responses are RFC 9457 `application/problem+json` bodies with the members `type`, `title`,
`status`, `detail`, and `instance`. No member is required by the schema, so parse defensively: the
HTTP status code is authoritative, and the body supplements it.

Quota `403` responses extend the problem with machine-readable extension members — `limit` (the
ceiling hit), `used` (current usage against it), and, for time-windowed quotas such as monthly ops,
`resetsAt` (when usage resets). These members are optional by design, so old consumers keep parsing
the bodies. `429` responses carry a `Retry-After` header (integer seconds).

A client should express four error roles in its own language's idiom — one catchable root with
three kinds beneath it:

- **api** — the server answered with an HTTP error response. Carries the status and the parsed
  problem details. The same kind is the right surface for a `2xx` whose body could not be parsed:
  it carries the real status of the unusable exchange.
- **validation** — rejected locally, before any network call: a bad key, a bad amount, an
  out-of-range parameter.
- **transport** — no HTTP response was ever obtained: DNS, connect, TLS, timeout, or retries
  exhausted. It carries no HTTP status, and callers must be able to distinguish it from `api`.

`conformance/errors/` pins exactly this mapping (§13).

## 9. Retry and backoff

- Honour `Retry-After` on `429` before retrying.
- Retry `5xx` responses and transport failures with exponential backoff and jitter (for example a
  ~250 ms base doubling to a ~30 s cap, with full jitter — the spec pins no exact schedule, so pick
  idiomatic values for your language).
- Retrying a **write** is safe inside the six-hour idempotency window, provided you resend the
  **same** idempotency key — that is what the key is for.
- **Never auto-retry the idempotency-cap `403`** (§6): it carries no `Retry-After` and the service
  promises no recovery time. Surface it instead.
- Other quota/plan `403`s are not retryable until usage resets or the plan changes; `resetsAt` on
  the problem tells you when for the monthly-ops quota.
- `409` means the idempotency key collided with a different operation: retry with a **fresh key**.
- `400` means the request is malformed: fix it; do not retry.
- Reads are freely retryable.

## 10. Pagination and time

`listCounters` pages in key order with an **opaque** `cursor` and a `limit` of 1–200 (default 50).
Follow `nextCursor` until it is absent — absence marks the last page. Never parse or construct a
cursor; pass it back verbatim.

All timestamps on the wire are RFC 3339 date-time strings: request bounds (`from`/`to`),
`occurredAt`, `createdAt`/`updatedAt`, series point `t` (bucket start, inclusive), `resetsAt`, and
the windowed board's `effectiveStart`/`effectiveEnd`. Series ranges are half-open: `[from, to)`.

`occurredAt` **back-dates a write into the series**: when set on an add/subtract/submit (standalone
or in a batch), the operation is bucketed at that instant instead of ingest time — the intended use
is offline spools flushing late. It is bounded: at most **5 minutes in the future** and no older
than the plan's retention window (`400` otherwise). Totals are unaffected — only series bucketing
moves.

## 11. A minimal worked example

One end-to-end flow in `curl`. Note that every amount and value stays a **string** throughout —
that is the §4 rule, modelled.

```sh
# 1. Authenticate with the full-access organization API key.
export COUNTERS_API_KEY="..."   # server-side only

# 2. Add to a counter, idempotently. The amount is larger than a u64, as a string.
curl -sS -X POST "https://api.counters.dev/v1/counters/signups/add" \
  -H "Authorization: Bearer $COUNTERS_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 9b1de3f0-2c54-4b6a-9f3e-2f7c1a55e001" \
  -d '{"amount":"18446744073709551616"}'
# → 200 {"key":"signups","value":"18446744073709551616","epoch":0, ...}
# Reissuing this exact request (same key) replays the same response — it is not
# applied twice.

# 3. Read the value back.
curl -sS "https://api.counters.dev/v1/counters/signups/value" \
  -H "Authorization: Bearer $COUNTERS_API_KEY"
# → 200 {"key":"signups","value":"18446744073709551616","epoch":0}

# 4. Flush a small batch — each operation carries its own idempotency key.
curl -sS -X POST "https://api.counters.dev/v1/batch" \
  -H "Authorization: Bearer $COUNTERS_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
        "operations": [
          {"counterKey":"signups","op":"add","amount":"3","idempotencyKey":"run-20260729-001"},
          {"counterKey":"signups","op":"add","amount":"4","idempotencyKey":"run-20260729-002"},
          {"counterKey":"scores","op":"add","member":"playerX","amount":"2500","idempotencyKey":"run-20260729-003"}
        ]
      }'

# 5. HTTP 200 means "accepted" — inspect each per-operation result.
# → 200 {"results":[
#      {"counterKey":"signups","status":"applied","value":"18446744073709551619"},
#      {"counterKey":"signups","status":"applied","value":"18446744073709551623"},
#      {"counterKey":"scores","status":"applied","value":"2500","memberValue":"2500"}
#    ]}
# A status may also be "deduplicated" (a retry replay) or "error" (an embedded
# problem object with its own status, e.g. 403 for the idempotency-key cap).
```

## 12. Not part of the client contract

Two surfaces appear in `openapi.yaml` for contract visibility but are **not** SDK surface, and a
client built from this guide does not implement them:

- **Dashboard browser surfaces.** `POST /dashboard/plane-token` (hub, WorkOS AuthKit auth) mints a
  short-lived `dt_` read token — valid at most 10 minutes — which the plane-local
  `GET /dashboard-read/*` endpoints accept at the data plane's origin root. They exist for the
  first-party web dashboard and are documentation-only here. Likewise, derived-counter *definitions*
  are managed on the dashboard; the machine API only reads them by key (§5).
- **Public key-less demo endpoints.** `GET /public/counters/{key}/value` and
  `POST /public/counters/{key}/tap` at the API origin root serve an operator-allowlisted set of
  demo counters (the landing page's global tap counter) with no authentication, per-IP and global
  rate limits, and permissive CORS. They are a first-party website feature: they exist, and they are
  out of scope for your client.

## 13. Prove your client

The [`conformance/`](./conformance) vectors are the behavioural half of the contract, and a new
client **should** replay them in its own test suite. They are language-agnostic JSON, designed to be
loaded by any test runner. What each pins:

- `counter-keys.json` — which counter keys are valid (the `^[A-Za-z0-9._:-]{1,200}$` rule).
- `member-keys.json` — which leaderboard member keys are valid (`^[A-Za-z0-9._:@|-]{1,256}$`) and
  the `metadata` 1024-**byte** cap (byte-counted, not character-counted).
- `amounts.json` — which `amount` strings are valid (non-negative arbitrary-precision integers).
- `bignum.json` — arbitrary-precision addition and subtraction, including values that overflow
  i64/u64 and net-zero/negative results. This is the headline rule (§4), proven.
- `buckets.json` — which series `bucket` strings a client accepts client-side (the fixed enum
  `1m, 5m, 1h, 1d, 1w, 1mo`) and which it rejects locally as a validation error before issuing a
  request. Plan-gating of finer buckets stays server-side.
- `series/` — series query encoding (RFC 3339 `from`/`to`, `gapfill` omitted when false,
  `mode`/`tz` passthrough, the `member`/`groupBy=member` variants) and response parsing for
  `SeriesResponse`, `MemberSeriesResponse`, and `MemberGroupSeriesResponse`.
- `leaderboard/` — board and window read query encoding, member add/subtract/submit request-body
  encoding, and parsing of `Leaderboard`, `WindowLeaderboard`, `MemberValue`, `MemberSnapshot`, and
  `MemberRemoved`.
- `derived/` — derived series query encoding and the decimal/`null` response shapes
  (`DerivedValueResponse`, `DerivedSeriesResponse`).
- `errors/` — the error-taxonomy mapping (§8): which catchable kind an HTTP error response (`api`),
  a no-response failure (`transport`), and a client-side rejection (`validation`) each surface as.
- `http/` — full request→response→state interaction vectors, plus a raw request→status validation
  matrix. These are replayed by the service's own suites and by the first-party SDKs' example apps
  against a live service; replaying them yourself needs a live deployment and an API key. The rest
  of the vectors are pure client-side encode/parse/validate checks and need no network.

Both `conformance/` and `openapi/` are synced read-only mirrors — do not edit them here; to propose
a new vector, open an issue describing it (see `CONTRIBUTING.md`). A client that passes the vectors
and tolerates unknown fields per §2 has done everything this contract asks.
