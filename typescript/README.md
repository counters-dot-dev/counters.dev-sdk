# counters.dev — TypeScript SDK

Official TypeScript SDK for [counters.dev](https://counters.dev), the
**arbitrary-precision** counter service.

- Pure TypeScript, **ESM**, **zero runtime dependencies** (uses the platform `fetch` and `crypto`).
- Buffered writes are coalesced per counter and flushed in the background; every write carries an
  `Idempotency-Key`, so retries are safe.
- Amounts and values are **arbitrary precision**: `bigint` internally, **strings on the wire**. The
  SDK never represents a counter value as a JS `number`. Decimal (derived) values stay strings too.
- Response timestamps (`createdAt` and `updatedAt`) are native JavaScript `Date` values.
- This is the **reference SDK** — the dashboard dogfoods it and the other language SDKs mirror its shape.

## The mental model

A **counter** is a named, signed integer that lives on the server. You address it by a key you
choose (`signups`, `api.requests`, `eu:orders`), add to it, subtract from it, read it, and roll it
up into a time series. It can go negative. `clear()` resets it to zero by starting a new **epoch**
(think: season) — history is retained, and leaderboard reads can address past epochs.

The defining property is **arbitrary precision** — and JavaScript is where that matters most,
because *every* JS `number` is an IEEE-754 double. Above 2<sup>53</sup>
(`Number.MAX_SAFE_INTEGER`, 9,007,199,254,740,992 — a number a busy event counter *will* reach)
integers silently round. counters.dev therefore puts every amount and value on the wire as a
**decimal string**, and this SDK keeps it that way: response values are `string`, amount inputs
accept `bigint | number | string` (a `number` is rejected unless it is a safe integer), and nothing
is ever routed through a float. The one thing you must not do is undo that: convert values with
`BigInt(value)`, never `Number(value)` or `parseFloat(value)`.

### Counter or leaderboard?

A leaderboard is not a separate product — it is the same counter with **per-member sub-values**.
Ask one question: *do I only care about the total, or do I care who contributed?*

- **Only the total** → a plain counter. `signups.add(1)` and you are done.
- **Per-contributor values, ranked** → the member surface of the same counter. Give each
  contributor a member key (`alice`, `tenant-42`, `page:/pricing`) and the counter becomes a board:
  every member holds its own value, the server ranks them, and `leaderboard()` returns the top-N
  with ranks and (on sum boards) the group total.

If you catch yourself creating one counter per user and sorting client-side, you wanted a
leaderboard.

Boards come in two flavours, fixed by the **first member write** and immutable afterwards:

- **Sum boards** (`member(...).add/subtract`) accumulate deltas per member — per-player damage,
  per-tenant API calls. The board keeps a group `total`.
- **Score boards** (`member(...).submit` with mode `latest`, `min`, or `max`) rank submitted
  scores — best lap time (`min`), high score (`max`). A worse-than-standing submit still succeeds
  and returns the standing value with `memberAccepted: false`.

## Install

```sh
npm install @counters.dev/sdk
```

> **Not yet published to npm.** Until it is, depend on it from a checkout of this repository:
> `"dependencies": { "@counters.dev/sdk": "file:path/to/counters.dev-sdk/typescript" }`

## Quickstart

```ts
import { CountersClient } from "@counters.dev/sdk";

const client = new CountersClient({ apiKey: process.env.COUNTERS_API_KEY! });
const registrations = client.counter("registrations");

registrations.add(1); // buffered + coalesced, flushed in the background

// Immediate, confirmed: applies now and returns the new counter state.
const state = await registrations.addNow("18446744073709551616"); // larger than a u64
console.log(state.value); // a decimal string, always
console.log(state.updatedAt?.toISOString()); // timestamps are Date values; counter timestamps are optional

const { value, epoch } = await registrations.value();

await client.close(); // flush buffered writes and stop the background worker before exit
```

Two kinds of write, deliberately:

- **Buffered** — `add`/`subtract`. Coalesced per counter client-side and flushed as one batch
  (every 1s or at 100 distinct counters, by default). Quotas meter *operations, not magnitude*, so
  coalescing a thousand `add(1)` calls into one `add 1000` costs one op. Failures are asynchronous;
  give `batch.onError` a sink or they are silent. Call `close()` before exit.
- **Immediate** — `addNow`/`subtractNow` (pass `occurredAt` to stamp an event time for
  late-arriving data). One request now, returning the new state. Every write carries a fresh
  idempotency key, so retries never double-count.

The runnable example app at [`examples/e2e/`](./examples/e2e/) drives **every public method** of
this SDK against a live server — it is the fastest way to see the whole surface in use.

## Reading a time series

A series is the **per-bucket delta**: how much the counter changed in each bucket of `[from, to)`,
not a running total. `bucket` is one of `1m | 5m | 1h | 1d | 1w | 1mo` (finer buckets are
plan-gated server-side). Empty buckets are omitted unless `gapfill: true` — treat a missing bucket
as zero. `tz` sets an IANA timezone so calendar buckets (`1d`, `1w`, `1mo`) break on local
boundaries.

```ts
const series = await registrations.series({
  from: "2026-07-04T00:00:00Z", // or a Date
  to: "2026-07-05T00:00:00Z",
  bucket: "1h",
  tz: "Europe/London", // optional
  gapfill: true, // optional
});
```

On a board you can slice by member — the return type follows the argument shape (requires member
series enabled on the counter):

```ts
await board.series({ from, to, bucket: "1h" });                    // SeriesResponse
await board.series({ from, to, bucket: "1h", member: "alice" });   // MemberSeriesResponse
await board.series({ from, to, bucket: "1h", groupBy: "member" }); // MemberGroupSeriesResponse
```

Setting both `member` and `groupBy` throws `CountersValidationError` before any request.

## Members and leaderboards

```ts
const board = client.counter("raid-dps");

// Sum board: accumulate deltas per member. Member writes are immediate — never buffered.
const alice = board.member("alice");
await alice.add(10);
await alice.subtract(3);
const snap = await alice.get(); // rank, percentile ("83.33" — a string), value, updatedAt (Date)
await alice.remove();

// Leaderboard (top-N, ranked). `total` is present only on sum boards.
const lb = await board.leaderboard({ limit: 25, order: "desc" });
lb.entries.forEach((e) => console.log(e.rank, e.member, e.value, e.updatedAt.toISOString()));
// value is a string; updatedAt is a required Date

// Score board: submit a signed score. `mode` is required on the first submit to a board.
const scores = client.counter("best-lap");
const r = await scores.member("alice").submit(1417, { mode: "min", metadata: "room1" });
console.log(r.memberAccepted); // false when the standing best was better

// Windowed leaderboard: rank trailing-window activity, not all-time standing.
const windowed = await board.leaderboard({ window: "7d" }); // 1h | 6h | 12h | 1d | 7d | 30d
```

Member writes carry optional `metadata` (≤ 1024 **UTF-8 bytes** — byte-counted, validated
client-side) and `occurredAt`.

## Derived counters

A **derived counter** is a server-defined, read-only expression over your counters (for example
`conversion = signups / visits`), evaluated at read time. Two things make it different from
everything above:

- It is **decimal**, not integer — the result is rounded to a fixed `scale`.
- Its value can be **null**. Division by zero does not throw and is not `"0"`; the SDK gives you
  `value: null` with a human-readable `reason`. Handle the null. In a series, a bucket that divided
  by zero is a `v: null` hole preserved in place.

```ts
const conversion = client.derived("conversion");
const v = await conversion.value(); // { value: string | null, scale, inputs, reason? }
if (v.value === null) console.warn(v.reason); // e.g. "division by zero" — never coerced to "0"
const ds = await conversion.series({ from, to, bucket: "1d" }); // points: { t, v: string | null }[]
```

Derived values are **signed decimals as strings** — never parse them with `Number()`/`parseFloat()`
(precision and fixed-scale loss).

## Errors: exactly three kinds

Every failure from this SDK is one of three classes, and the distinction tells you what to do:

| Class | Meaning | Typical handling |
|---|---|---|
| `CountersValidationError` | Rejected client-side (bad key, negative amount, over-cap metadata, bad `bucket`/`window`, `member`+`groupBy`) — **no request was made** | A bug in your code; fix the input |
| `CountersApiError` | The server answered with an HTTP error; `status` is always the real status and `problem` is the parsed RFC 9457 body | Branch on `status` (403 quota, 404 missing, 409 conflict…) |
| `CountersTransportError` | **No response was ever obtained** — network failure or timeout, retries exhausted; never carries a status | Infrastructure problem; back off and retry later |

All three extend `CountersError`, so `catch (e) { if (e instanceof CountersError) … }` catches
anything originating in this SDK.

Retries are built in: network errors and HTTP 429/5xx retry with exponential backoff (default 3
retries), honouring `Retry-After`; `timeoutMs` bounds each attempt (default 30s). Idempotency keys
make retried writes safe.

## Odds and ends

- **Usage**: `client.usage()` returns month-to-date ops, quota, reset instant, and counter
  headroom. Poll it periodically, not per write.
- **Publishable tokens**: a read-only `pk_` token can be used as the `apiKey` for embedding public
  reads (values, series, leaderboards) of the counters it is scoped to; writes — and reads outside
  its scope — fail with a 403 `CountersApiError`.
- **Validation helpers**: `isValidCounterKey`, `isValidMemberKey`, `isValidMetadata`, `BUCKETS`,
  `WINDOWS` are exported so you can pre-check user-supplied names.

## Compatibility

Requires a runtime with global `fetch` and `crypto.randomUUID` (Node 20+, Deno, Bun, modern
browsers). Zero runtime dependencies.
