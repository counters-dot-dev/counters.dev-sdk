# HTTP interaction vectors

Language-agnostic request→response→state vectors. One dataset,
asserted by **two kinds of runner**:

1. the API implementation's own replay suites, which live in the (private) service repository;
2. every SDK's example-app end-to-end app (`<lang>/examples/e2e/`), which replays the `"scope": "all"`
   cases through the **real client library** against a live service.

In this repository the example apps are compiled but not run against a service — see `CONTRIBUTING.md`.

Adding a case here fans out to all of them with no code change.

## Files

- `cases.json` — op-level stateful cases. Each case is a list of `steps`; each step is a client
  operation plus expectations. Cases with `"scope": "all"` are replayable through an SDK's public
  surface; `"scope": "http"` cases use capabilities SDKs deliberately do not expose (raw batch
  composition, custom idempotency keys, counter-metadata GET) and are run by the service's own
  suites only.
- `requests.json` — raw HTTP validation matrix (malformed input → status). Service suites only.

**Derived counters** (`/v1/derived/{key}/value|series`) have no `cases.json` happy-path vectors: a
happy-path vector would need a *definition*, which is created only on the dashboard plane (JWT) that
these runners can't reach. The `derived-*` entries in `requests.json` (401/400/404 matrix), plus the
service's own suites (auth classes, rate limiting, and the full happy path with its numeric
semantics), cover the HTTP contract. The SDK-facing encode/parse contract — query
params and the decimal/`null` response shapes — is pinned client-side in
[`conformance/derived/`](../derived/README.md); every SDK implements the read surface.

**Dimensional member series** (`series?member=`, `series?groupBy=member`,
`leaderboard?window=`) have no `cases.json` happy-path vectors. Member series can be enabled through
either the full-API-key operation `PUT /v1/counters/{key}/member-series` or the audited dashboard
operation `PUT /dashboard/counters/{key}/member-series`; OpenAPI owns the former's exact configuration
contract. The dimensional-read HTTP contract is covered by the `member-series-*` /
`window-leaderboard-*` entries in `requests.json` (401 + route-level 400 guards: opt-in required,
`member`+`groupBy` mutually exclusive, bad `groupBy`, top without groupBy, invalid `other`), plus the
service's own suites (auth classes, API-key configuration and epoch/provenance semantics, rate
limiting, the `pk_` read surface, and the full dimensional-read happy path:
bucket exactness, mode-aware top-N selection, point-budget preflight, the sum-only `$other` tail,
selection metadata, windowed ranking, `pk_` embed + CORS, bucket floor, window labels, and
cross-tenant isolation). The
SDK-facing base dimensional encode/parse contract is pinned client-side instead: the
`member`/`groupBy` query cases and `MemberSeriesResponse`/`MemberGroupSeriesResponse` parse cases in
[`conformance/series/`](../series/README.md), and the leaderboard `window=` read + `WindowLeaderboard`
parse cases in [`conformance/leaderboard/`](../leaderboard/README.md). The REST-only `top`/`other`
parameters are owned by OpenAPI and the service suites. Every SDK implements the base dimensional
surface (drift guard: the reads are query-param variants of
`getCounterSeries`/`getCounterLeaderboard`, so no new operationIds).

**Score-mode dimensional reads** (`min`/`max`/`latest` member series
and windowed boards) inherit that same resolution: they still require member series *enabled*, so
there are no `cases.json` happy-path vectors for them either. Their end-to-end contract — mode-correct
aggregation (per-bucket min/max/latest), the
`mode` response field (`min`/`max`/`latest` on score series vs `delta` on sum; the board mode on the
window board), score-series `gapfill=true` → 400, sparse `groupBy` points, mode-driven default order
(`asc` on `min` windows), and the omitted (`null`) score `total` — is pinned by the service's own
suites (the full happy path over a real stack, plus the recompute-from-events and
window-vs-all-time invariants).

## Op → HTTP mapping (`cases.json`)

| `op` | HTTP | SDK method (canonical TS names) |
|---|---|---|
| `add` | `POST /v1/counters/{key}/add` `{amount, occurredAt?}` | `counter(key).addNow(amount, {occurredAt})` |
| `subtract` | `POST /v1/counters/{key}/subtract` | `counter(key).subtractNow(...)` |
| `clear` | `POST /v1/counters/{key}/clear` | `counter(key).clear()` |
| `delete` | `DELETE /v1/counters/{key}` | `counter(key).delete()` |
| `value` | `GET /v1/counters/{key}/value` | `counter(key).value()` |
| `get` | `GET /v1/counters/{key}` | *(no SDK method — scope `http`)* |
| `list` | `GET /v1/counters?limit&cursor` (walk all pages) | `client.list(...)` looped |
| `series` | `GET /v1/counters/{key}/series?...` | `counter(key).series(...)` |
| `usage` | `GET /v1/usage` | `client.usage()` |
| `batch` | `POST /v1/batch` | *(SDK batching is internal — scope `http`)* |
| `memberAdd` / `memberSubtract` | `POST /v1/counters/{key}/members/{member}/{add\|subtract}` `{amount, metadata?, occurredAt?}` | `counter(key).member(member).add/subtract(...)` |
| `memberSubmit` | `POST /v1/counters/{key}/members/{member}/submit` `{value, mode?, metadata?, occurredAt?}` | `counter(key).member(member).submit(...)` |
| `memberGet` | `GET /v1/counters/{key}/members/{member}?epoch&order` | `counter(key).member(member).get(...)` |
| `memberRemove` | `DELETE /v1/counters/{key}/members/{member}` | `counter(key).member(member).remove()` |
| `leaderboard` | `GET /v1/counters/{key}/leaderboard?limit&offset&order&epoch` | `counter(key).leaderboard(...)` |

Leaderboard ops (`scope: "all"`) take extra `do` fields: `member` (literal, **not** namespaced —
member keys live inside a counter's board), `value`/`mode` (submit), `metadata`, and read params
`limit`/`offset`/`order`/`epoch`. Their `expect` supports: `memberValue`, `memberAccepted`, `mode`,
`order`, `total`, `memberCount`, `rank`, `percentile`, `metadata`, and `entries` (an ordered array of
`{rank, member, value, metadata?}` — each field asserted only when present).

The `usage` op reads `GET /v1/usage` (org quota state; no `key`). Its `expect.usage` object is
**tolerant** — the endpoint reports the whole org's month-to-date, and other cases write into the same
org — so it asserts lower bounds and presence, never exact counts: `opsUsedAtLeast` (≤ `ops.used`),
`countersUsedAtLeast` (≤ `counters.used`), and `hasResetsAt` (whether `ops.resetsAt` is present).
`usage` is `scope: "all"` and reachable with only an API key, so SDK example apps replay it through
`client.usage()`. The separate `usage-requires-full-key` case is `scope: "http"`: it sets
`do.auth: "pk"` — the runner authenticates that step with a **publishable (`pk_`) read-only token**
for the org instead of the full key — and asserts `403`, because `/v1/usage` requires full access
(publishable tokens are read-only counter-scoped and cannot read org quota state). `do.auth` defaults
to the org's full API key when absent; `"pk"` is its only other value. Provisioning a `pk_` token is
dashboard-plane (JWT), so only the service's own runners implement `auth: "pk"` (they provision a
publishable token directly) — an
`auth: "pk"` step therefore only ever appears in a `scope: "http"` case.

## Step schema

```jsonc
{
  "do": {
    "org": "A",              // org label; runners provision/receive one API key per label
    "op": "add",
    "auth": "pk",             // optional; default = org's full API key. "pk" = a publishable read-only token (scope-http only)
    "key": "basic",           // ALWAYS namespaced by the runner (see rules below)
    "amount": "5",            // decimal string; add/subtract only
    "occurredAtMin": -120,     // optional; minutes relative to the runner's t0
    "idempotencyKey": "k1",   // optional; scope-http ops only
    "series": { "fromMin": -1440, "toMin": 60, "bucket": "1h", "gapfill": false, "tz": "UTC" },
    "list": { "limit": 2 },
    "batch": [ { "key": "x", "op": "add", "amount": "5", "idempotencyKey": "b1" } ]
  },
  "expect": {
    "status": 200,            // required
    "value": "5",             // counter/value bodies
    "epoch": 0,
    "headers": { "Content-Type": "application/problem+json; charset=UTF-8" }, // op-level header assertions
    "key": "basic",           // response key must equal the (namespaced) key
    "pointsSum": "8",         // series: Σ point values in the response
    "pointsAtLeast": 2,        // series: minimum number of points
    "containsInOrder": ["a","b"], // list walk: these (namespaced) keys appear as a subsequence
    "usage": {                 // GET /v1/usage — tolerant lower-bound + presence assertions
      "opsUsedAtLeast": 3,      //   ops.used >= 3
      "countersUsedAtLeast": 1, //   counters.used >= 1
      "hasResetsAt": true       //   ops.resetsAt is present
    },
    "results": [               // batch: per-op result assertions, in order
      { "status": "applied", "value": "5" },
      { "status": "error", "errorStatus": 400 }
    ]
  }
}
```

An expected `status` outside 2xx means the runner asserts the operation **fails** with that HTTP
status (SDK runners assert their typed error carries it). `headers` assertions are runner-level HTTP
checks and are not part of SDK example-app replay. Fields omitted from `expect` are not asserted.

## Runner rules

- **Namespace every counter key** (including inside `batch` and `containsInOrder`) with a
  run-unique prefix, e.g. `e2e-<runId>-c<case#>-`. Keys are then fresh, so absolute `epoch`
  expectations (0 on first write, 1 after clear) hold even against a long-lived server. The
  prefix must keep the key inside `^[A-Za-z0-9._:-]{1,200}$`.
- **Org labels** (`A`, `B`) map to organizations on the **pro** plan (1m resolution, high caps) so
  vector traffic never trips counter caps or rate limits. In-process/black-box runners provision
  them; SDK example apps get one API key per label from the environment.
- **`t0`** is captured once per run (UTC, truncated to seconds); `*Min` offsets resolve against it.
- **Steps run in order**; a step whose expectation fails aborts the case. Cases are independent.
- **`list` walks**: issue `list` with the given `limit`, follow `nextCursor` until absent,
  concatenate page keys, then apply `containsInOrder` as a subsequence check (other counters may
  legitimately coexist in the org).
- Series windows are chosen so bucket-boundary alignment cannot change the expected sums; do not
  add a case whose expectation depends on where `t0` falls inside a bucket.

## `requests.json` schema

```jsonc
{
  "name": "add-negative-amount-400",
  "request": {
    "method": "POST",
    "path": "/v1/counters/never-written/add",  // literal; no namespacing (runs assume a fresh org/DB)
    "auth": "A",                                  // "A"/"B" = that org's key; "garbage" = a syntactically fine but
                                                   // unknown key; "empty" = literal "Bearer " (empty token); null = no header
    "headers": { "Idempotency-Key": "..." },     // optional extra request headers
    "body": { "amount": "-1" },                  // JSON body, or
    "rawBody": "{\"amount\": not-json",          // a literal non-JSON body
    "contentType": "application/json"             // optional override
  },
  "expect": { "status": 400, "headers": { "Access-Control-Allow-Origin": "*" } }
}
```

Size-cap cases (413 oversize body, batch > 1000 ops) stay in code — a vector file carrying a
megabyte of `x`s helps no one; they are asserted by the service's own test suite.
