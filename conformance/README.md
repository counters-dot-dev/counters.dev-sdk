# Conformance test vectors

> **Synced, read-only.** This directory is mirrored in from the counters.dev service repository,
> which is its source of truth. Do not edit it here — see [`SYNCED.md`](./SYNCED.md).

Language-agnostic test data shared by **every** implementation — the counters.dev service and all
three client SDKs (TypeScript, Java, Go). The OpenAPI spec defines the *shape* of the API; these
vectors pin the *behaviour* of the shared rules so every implementation agrees:

- `counter-keys.json` — which counter keys are valid (the `^[A-Za-z0-9._:-]{1,200}$` rule).
- `member-keys.json` — which leaderboard **member** keys are valid (`^[A-Za-z0-9._:@|-]{1,256}$`) and
  the `metadata` 1024-**byte** cap (byte-counted, not character-counted).
- `amounts.json` — which `amount` strings are valid (non-negative arbitrary-precision integers).
- `bignum.json` — arbitrary-precision **addition and subtraction** cases, including values that
  overflow i64/u64 and net-zero/negative results (the headline feature).
- `errors/` — **error-taxonomy vectors**: which catchable type each SDK surfaces for an HTTP error
  response (`api`), a no-response failure (`transport`), or a client-side rejection (`validation`).
  See `errors/README.md`.
- `buckets.json` — **series bucket-size vectors** (B6): which `bucket` strings each SDK accepts
  client-side (the fixed spec enum `1m,5m,1h,1d,1w,1mo`) and which it rejects locally as a validation
  error before issuing the request. Plan-gating of finer buckets stays server-side.
- `series/` — **series query-encoding + response-parse vectors** (B8): series params → the exact
  query-string parameters (from/to RFC-3339, `gapfill` omitted when false, `mode`/`tz` passthrough)
  and a `SeriesResponse` body → parsed points, plus the dimensional `member=`/`groupBy=member`
  variants. See `series/README.md`.
- `leaderboard/` — **client-side leaderboard/member encode + parse vectors**:
  board/window read query encoding, member add/subtract/submit request-body encoding, and response
  parsing (`Leaderboard`, `WindowLeaderboard`, `MemberValue`, `MemberSnapshot`, `MemberRemoved`). The
  stateful ranking behaviour is in `http/` at `scope:"http"`. See `leaderboard/README.md`.
- `derived/` — **client-side derived-counter encode + parse vectors**: derived
  series query encoding and the decimal/`null` response shapes (`DerivedValueResponse`,
  `DerivedSeriesResponse`). See `derived/README.md`.
- `http/` — **HTTP interaction vectors**: op-level stateful cases plus a raw request→status
  validation matrix, replayed by the service's own suites (in the private service repository)
  and by every SDK's example-app end-to-end app (`<lang>/examples/e2e`). See
  `http/README.md` for the schema and runner rules.

Each test suite loads these files and asserts against them, so a divergence between the service and
any SDK is caught mechanically rather than in production. Every `sdk-*-ci.yml` re-triggers on
`conformance/**` — adding a vector fans out to every suite with no code change.
