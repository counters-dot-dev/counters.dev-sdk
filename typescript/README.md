# counters — TypeScript SDK

Official TypeScript SDK for [counters.dev](https://counters.dev), the multi-tenant
**arbitrary-precision** counter service.

- Pure TypeScript, **ESM**, **zero runtime dependencies** (uses the platform `fetch` and `crypto`).
- Buffered writes are coalesced per counter and flushed in the background; every write carries an
  `Idempotency-Key`, so retries are safe.
- Amounts and values are **arbitrary precision**: `bigint` internally, **strings on the wire**. The
  SDK never represents a counter value as a JS `number`. Decimal (derived) values stay strings too.
- This is the **reference SDK** — the dashboard dogfoods it and the other language SDKs mirror its shape.

## Install

**Not yet published to npm.** Until it is, depend on it from a checkout of this repository:

```jsonc
// package.json
{
  "dependencies": { "@counters.dev/sdk": "file:path/to/counters.dev-sdk/typescript" }
}
```

## Quickstart

```ts
import { CountersClient } from "@counters.dev/sdk";

const client = new CountersClient({ apiKey: process.env.COUNTERS_API_KEY! });
const registrations = client.counter("registrations");

// Buffered, fire-and-forget: coalesced per counter, flushed in the background.
registrations.add(1);
registrations.subtract(1);

// Immediate, confirmed: applies now and returns the new counter state.
const state = await registrations.addNow("18446744073709551616"); // amounts can exceed u64
console.log(state.value); // arbitrary-precision value, always a string

// Reads.
const { value, epoch } = await registrations.value();
const series = await registrations.series({
  from: "2026-07-04T00:00:00Z",
  to: "2026-07-05T00:00:00Z",
  bucket: "1h", // 1m | 5m | 1h | 1d | 1w | 1mo
  tz: "Europe/London", // optional
  gapfill: true, // optional
});

// Flush buffered writes and stop the background worker before exit.
await client.close();
```

## API summary

### Client

| Method | Returns | Notes |
|---|---|---|
| `client.counter(key)` | `CounterHandle` | Validates the key client-side. |
| `client.list({ cursor?, limit? })` | `CounterPage` | Follow `nextCursor` to page. |
| `client.usage()` | `Usage` | Org quota state — poll periodically, not per-write. |
| `client.derived(key)` | `DerivedHandle` | A read-only decimal expression over counters. |
| `client.flush()` / `client.close()` | `Promise<void>` | Flush buffered writes; `close` also stops the timer. |

### Counter handle

`add` / `subtract` (buffered), `addNow` / `subtractNow` (immediate), `clear`, `delete`, `value`,
`series`, plus the **leaderboard/member** surface:

```ts
const board = client.counter("raid-dps");

// Leaderboard (top-N, ranked). `total` is present only on sum boards.
const lb = await board.leaderboard({ limit: 25, order: "desc" });
lb.entries.forEach((e) => console.log(e.rank, e.member, e.value)); // value is a string

// Windowed leaderboard: rank trailing-window activity (requires member series enabled).
const windowed = await board.leaderboard({ window: "7d" }); // 1h | 6h | 12h | 1d | 7d | 30d

// A member handle. Member keys are validated client-side.
const alice = board.member("alice");
await alice.add(10);                                   // sum board: accumulate a delta (immediate)
await alice.subtract(3);
const snap = await alice.get();                        // rank, percentile ("83.33" — a string), value
await alice.remove();

// Score boards: submit a signed score. `mode` is required on the first submit to a board.
const scores = client.counter("best-time");
const r = await scores.member("alice").submit(1417, { mode: "min", metadata: "room1" });
console.log(r.memberAccepted); // false when a worse-than-standing min/max submit is kept out
```

Member writes on the typed surface are **immediate** (not buffered) and carry `metadata` (≤ 1024
**UTF-8 bytes**, validated client-side) and an optional `occurredAt`.

### Dimensional series (`series` overloads)

Requires member series enabled on the counter. The return type follows the argument shape:

```ts
await board.series({ from, to, bucket: "1h" });                    // SeriesResponse
await board.series({ from, to, bucket: "1h", member: "alice" });   // MemberSeriesResponse
await board.series({ from, to, bucket: "1h", groupBy: "member" }); // MemberGroupSeriesResponse
```

Setting both `member` and `groupBy` throws `CountersValidationError` before any request.

### Derived counters

```ts
const conversion = client.derived("conversion");
const v = await conversion.value();  // { value: string | null, scale, inputs, reason? }
if (v.value === null) console.warn(v.reason); // e.g. "division by zero" — never coerced to "0"
const ds = await conversion.series({ from, to, bucket: "1d" }); // points: { t, v: string | null }[]
```

Derived values are **signed decimals as strings** (never floats); a bucket that divided by zero has
`v: null` preserved in place.

## Errors

| Class | Thrown when |
|---|---|
| `CountersValidationError` | A client-side check failed (bad key, negative amount, over-cap metadata, bad `window`, `member`+`groupBy`) — **before** any request. |
| `CountersApiError` | The server returned an HTTP error; `status` and the RFC 9457 `problem` are attached. |
| `CountersTransportError` | No HTTP response was obtained (network error / timeout, retries exhausted). Never carries a status. |
| `CountersError` | Base class of all three. |

## Compatibility

Requires a runtime with global `fetch` and `crypto.randomUUID` (Node 20+, Deno, Bun, modern browsers).
Zero runtime dependencies.
