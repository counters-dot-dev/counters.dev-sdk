# counters.dev — Go SDK

Official Go SDK for [counters.dev](https://counters.dev), the **arbitrary-precision**
counter service. Pure standard library — zero dependencies.

```sh
go get github.com/counters-dot-dev/counters.dev-sdk/go
```

> **Not yet published.** Until the first tagged release, `go get` will not resolve; depend on a
> checkout of this repository with a `replace` directive in your `go.mod`.

## The mental model

A **counter** is a named, signed integer that lives on the server. You address it by a key you
choose (`signups`, `api.requests`, `eu:orders`), add to it, subtract from it, read it, and roll it
up into a time series. It can go negative. `Clear` resets it to zero by starting a new **epoch**
(think: season) — history is retained, and leaderboard reads can address past epochs.

The defining property is **arbitrary precision**. A JSON number is an IEEE-754 double, and above
2<sup>53</sup> (9,007,199,254,740,992 — a number a busy event counter *will* reach) doubles silently
round. counters.dev therefore puts every amount and value on the wire as a **decimal string**, and
this SDK keeps it that way: response values are `string` fields, amount inputs accept `int`,
`int64`, `string`, or `*big.Int`, and nothing is ever routed through a `float64`. The one thing you
must not do is undo that: parse values with `new(big.Int).SetString(v, 10)` (or keep them as
strings), never `strconv.ParseFloat` or `json.Number.Float64`.

### Counter or leaderboard?

A leaderboard is not a separate product — it is the same counter with **per-member sub-values**.
Ask one question: *do I only care about the total, or do I care who contributed?*

- **Only the total** → a plain counter. `signups.Add(1)` and you are done.
- **Per-contributor values, ranked** → the member surface of the same counter. Give each
  contributor a member key (`alice`, `tenant-42`, `page:/pricing`) and the counter becomes a board:
  every member holds its own value, the server ranks them, and `Leaderboard` gives you the top-N
  with ranks and (on sum boards) the group total.

If you catch yourself creating one counter per user and sorting client-side, you wanted a
leaderboard.

Boards come in two flavours, fixed by the **first member write** and immutable afterwards:

- **Sum boards** (`Member(...).Add/Subtract`) accumulate deltas per member — per-player damage,
  per-tenant API calls. The board keeps a group `Total`.
- **Score boards** (`Member(...).Submit` with mode `latest`, `min`, or `max`) rank submitted
  scores — best lap time (`min`), high score (`max`). A worse-than-standing submit still succeeds
  and returns the standing value with `MemberAccepted == false`.

## Quickstart

```go
client, err := counters.NewClient(counters.Options{APIKey: os.Getenv("COUNTERS_API_KEY")})
if err != nil {
	log.Fatal(err)
}
defer client.Close() // flushes buffered writes

signups, err := client.Counter("signups")
if err != nil {
	log.Fatal(err) // invalid key — rejected before any request
}

_ = signups.Add(1) // buffered + coalesced, flushed in the background

state, err := signups.AddNow(ctx, "18446744073709551616") // immediate, confirmed — larger than a u64
if err != nil {
	log.Fatal(err)
}
fmt.Println(state.Value) // a decimal string, always
```

Two kinds of write, deliberately:

- **Buffered** — `Add`/`Subtract`. Coalesced per counter client-side and flushed as one batch
  (every 1s or at 100 distinct counters, by default). Quotas meter *operations, not magnitude*, so
  coalescing a thousand `Add(1)` calls into one `add 1000` costs one op. Failures are asynchronous;
  give `BatchOptions.OnError` a sink or they are silent. Call `Close` before exit.
- **Immediate** — `AddNow`/`SubtractNow` (and `AddNowAt`/`SubtractNowAt` to stamp an event time for
  late-arriving data). One request now, returning the new state. Every write carries a fresh
  idempotency key, so retries never double-count.

The runnable example app at [`examples/e2e/`](./examples/e2e/) drives **every public method** of
this SDK against a live server — it is the fastest way to see the whole surface in use.

## Reading a time series

A series is the **per-bucket delta**: how much the counter changed in each bucket of `[From, To)`,
not a running total. `Bucket` is one of `1m`, `5m`, `1h`, `1d`, `1w`, `1mo` (finer buckets are
plan-gated server-side). Empty buckets are omitted unless `Gapfill: true` — treat a missing bucket
as zero. `Tz` sets an IANA timezone so calendar buckets (`1d`, `1w`, `1mo`) break on local
boundaries.

```go
series, err := signups.Series(ctx, counters.SeriesParams{From: from, To: to, Bucket: "1h"})
```

On a board you can slice by member: `MemberSeries(ctx, "alice", params)` for one member's series,
`GroupSeries(ctx, params)` for the dense per-member multi-series (both require member series
enabled on the counter).

## Members and leaderboards

```go
board, _ := client.Counter("raid-dps")
alice, _ := board.Member("alice")

_, err = alice.Add(ctx, 10) // sum board: accumulate (immediate — member writes are never buffered)
snap, _ := alice.Get(ctx, counters.MemberGetParams{})
fmt.Println(snap.Rank, snap.Percentile) // percentile is a scale-2 string, e.g. "83.33"

top, _ := board.Leaderboard(ctx, counters.LeaderboardParams{Limit: 25, Order: "desc"})
for _, e := range top.Entries {
	fmt.Println(e.Rank, e.Member, e.Value)
}

// Score board: best (lowest) time wins.
best, _ := client.Counter("best-lap")
lap, _ := best.Member("alice")
r, _ := lap.Submit(ctx, 1417, counters.SubmitOpts{Mode: "min"})
fmt.Println(r.MemberAccepted) // false when the standing best was better

// Trailing-window board: rank recent activity instead of all-time standing.
recent, _ := board.WindowLeaderboard(ctx, counters.WindowLeaderboardParams{Window: "7d"})
```

Fields the API may omit are pointers: `Leaderboard.Total` (sum boards only), `MemberValue.Value`,
entry/snapshot `Metadata`, and the usage quota fields — `nil` means "not present", never zero.

## Derived counters

A **derived counter** is a server-defined, read-only expression over your counters (for example
`conversion = signups / visits`), evaluated at read time. Two things make it different from
everything above:

- It is **decimal**, not integer — the result is rounded to a fixed `Scale`.
- Its value can be **null**. Division by zero does not error and is not `"0"`; the SDK gives you
  `Value == nil` with a human-readable `Reason`. Handle the nil. In a series, a bucket that divided
  by zero is a `nil` hole preserved in place.

```go
conv, _ := client.Derived("conversion")
cur, err := conv.Value(ctx)
if err != nil {
	return err
}
if cur.Value == nil {
	fmt.Println("no value:", *cur.Reason) // e.g. "division by zero"
} else {
	fmt.Println(*cur.Value) // a decimal string — do not ParseFloat it
}
```

## Errors: exactly three kinds

Every failure from this SDK is one of three types, and the distinction tells you what to do:

| Type | Meaning | Typical handling |
|---|---|---|
| `*counters.ValidationError` | Rejected client-side (bad key, negative amount, oversized metadata, bad bucket/window) — **no request was made** | A bug in your code; fix the input |
| `*counters.APIError` | The server answered with an HTTP error; `Status` is always the real status | Branch on `Status` (403 quota, 404 missing, 409 conflict…) |
| `*counters.TransportError` | **No response was ever obtained** — network failure or timeout, retries exhausted; carries no status | Infrastructure problem; back off and retry later |

All three implement the `counters.Error` marker interface, so use `errors.As`:

```go
var apiErr *counters.APIError
if errors.As(err, &apiErr) && apiErr.Status == 404 { /* counter does not exist */ }

var anySDK counters.Error
if errors.As(err, &anySDK) { /* any failure originating in this SDK */ }
```

Retries are built in: connect errors and HTTP 429/5xx retry with exponential backoff (default 3
retries; `MaxRetries: -1` disables), honouring `Retry-After`. Idempotency keys make retried writes
safe. Writes after `Close` return `ErrClientClosed` (`errors.Is`).

## Odds and ends

- **Usage**: `client.Usage(ctx)` returns month-to-date ops, quota, reset instant, and counter
  headroom. Poll it periodically, not per write.
- **Publishable tokens**: a read-only `pk_` token can be used as the `APIKey` for embedding public
  reads (values, series, leaderboards) of the counters it is scoped to; writes — and reads outside
  its scope — fail with a 403 `APIError`.
- **Validation helpers**: `IsValidCounterKey`, `IsValidMemberKey`, `IsValidMetadata`, `Buckets`,
  `Windows` are exported so you can pre-check user-supplied names.

## Development

```sh
go test ./...
go vet ./...
```

The behaviour is pinned by the shared vectors in [`../conformance/`](../conformance/) and the wire
contract by [`../openapi/openapi.yaml`](../openapi/); the suite replays both.
