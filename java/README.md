# counters.dev — Java SDK

Official Java SDK for [counters.dev](https://counters.dev) — the **arbitrary-precision** counter service. Pure JDK 17 standard library, zero runtime dependencies.

## The mental model

A **counter** is a named, signed integer that lives on the server. You address it by a key you
choose (`signups`, `api.requests`, `eu:orders`), add to it, subtract from it, read it, and roll it
up into a time series. It can go negative. `clear()` resets it to zero by starting a new **epoch**
(think: season) — history is retained, and leaderboard reads can address past epochs.

The defining property is **arbitrary precision**. A JSON number is an IEEE-754 double, and above
2<sup>53</sup> (9,007,199,254,740,992 — a number a busy event counter *will* reach) doubles
silently round. counters.dev therefore puts every amount and value on the wire as a **decimal
string**, and this SDK keeps it that way: response values are `String` fields, amount arguments
accept `long`, decimal `String`, or `BigInteger`, and nothing is ever routed through a `double`.
The one thing you must not do is undo that: parse values with `new BigInteger(value)` (or
`new BigDecimal(value)` for derived values), never `Double.parseDouble` or `Long.parseLong` unless
you know the magnitude fits.

Every machine-SDK date-time is a native `java.time.Instant`: request bounds and optional `occurredAt`
values, series ranges and point timestamps, usage reset time, window-leaderboard boundaries, and
the `createdAt`/`updatedAt` response fields. Optional date-times remain `null` when absent.
`Counter.createdAt()` and `Counter.updatedAt()` are nullable because those fields are optional;
`LeaderboardEntry.updatedAt()` and `MemberSnapshot.updatedAt()` are required instants.

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
  and returns the standing value with `memberAccepted() == false`.

## Install

> **Not yet published.** Maven Central coordinates will be `dev.counters:counters-sdk:0.1.0`. Until
> then, build the jar locally (`gradle build` → `build/libs/counters-sdk-0.1.0.jar`) or consume this
> directory as a Gradle [composite build](https://docs.gradle.org/current/userguide/composite_builds.html).

```kotlin
// build.gradle.kts — once published
dependencies {
    implementation("dev.counters:counters-sdk:0.1.0")
}
```

## Quickstart

```java
import dev.counters.sdk.*;

try (CountersClient client = CountersClient.builder()
        .apiKey(System.getenv("COUNTERS_API_KEY"))
        .build()) {

    CounterHandle registrations = client.counter("registrations");

    registrations.add(1); // buffered + coalesced, flushed in the background

    // Immediate, confirmed: applies now and returns the new state.
    Counter c = registrations.addNow("18446744073709551616"); // larger than a u64
    System.out.println(c.value()); // a decimal string, always

    ValueResponse v = registrations.value();
} // close() flushes buffered writes and stops the background timer
```

Two kinds of write, deliberately:

- **Buffered** — `add`/`subtract`. Coalesced per counter client-side and flushed as one batch
  (every 1s or at 100 distinct counters, by default). Quotas meter *operations, not magnitude*, so
  coalescing a thousand `add(1)` calls into one `add 1000` costs one op. Failures are asynchronous;
  give `onBatchError` a sink or they are silent. The flush thread is a daemon — it never keeps the
  JVM alive, which also means you must `close()` (try-with-resources) before exit.
- **Immediate** — `addNow`/`subtractNow` (pass an `Instant occurredAt` to stamp an event
  time for late-arriving data). One request now, returning the new state. Every write carries a
  fresh idempotency key, so retries never double-count.

The runnable example app at [`examples/e2e/`](./examples/e2e/) drives **every public method** of
this SDK against a live server — it is the fastest way to see the whole surface in use.

## Reading a time series

A series is the **per-bucket delta**: how much the counter changed in each bucket of `[from, to)`,
not a running total. The bucket is one of `1m`, `5m`, `1h`, `1d`, `1w`, `1mo` (finer buckets are
plan-gated server-side). Empty buckets are omitted unless gapfill is requested — treat a missing
bucket as zero. `SeriesParams.timeZone()` sets an IANA timezone so calendar buckets (`1d`, `1w`,
`1mo`) break on local boundaries (the SDK maps it to the compact `tz` wire key).

```java
Instant to = Instant.now();
SeriesResponse s = registrations.series(new SeriesParams(
        to.minusSeconds(86_400), to, "1h"));
Instant coveredFrom = s.range().from();
for (SeriesPoint point : s.points()) {
    Instant bucketStart = point.timestamp();
    BigInteger delta = new BigInteger(point.value());
}
```

On a board you can slice by member: `memberSeries(member, params)` for one member's series,
`groupSeries(params)` for the dense per-member multi-series (both require member series enabled on
the counter).

## Members and leaderboards

```java
CounterHandle board = client.counter("raid-dps");

// Sum board: accumulate deltas per member. Member writes are immediate — never buffered.
MemberHandle alice = board.member("alice");
alice.add(10);
MemberSnapshot snap = alice.get(); // rank, percentile ("83.33" — a string), value

// Leaderboard (top-N, ranked). total() is non-null only on sum boards.
Leaderboard top = board.leaderboard(new LeaderboardParams(25, null, "desc", null));
for (LeaderboardEntry e : top.entries()) {
    System.out.println(e.rank() + " " + e.member() + " " + e.value()); // value is a string
}

// Score board: submit a signed score. Mode is required on the first submit to a board.
MemberValue r = client.counter("best-lap").member("alice")
        .submit(1417, new SubmitOptions("min"));
System.out.println(r.memberAccepted()); // false when the standing best was better

// Windowed leaderboard: rank trailing-window activity, not all-time standing.
WindowLeaderboard recent = board.windowLeaderboard(new WindowLeaderboardParams("7d"));
```

Member writes carry optional `metadata` (≤ 1024 **UTF-8 bytes** — byte-counted, validated
client-side) and `occurredAt` via `MemberWriteOptions` / `SubmitOptions`.

## Derived counters

A **derived counter** is a server-defined, read-only expression over your counters (for example
`conversion = signups / visits`), evaluated at read time. Two things make it different from
everything above:

- It is **decimal**, not integer — the result is rounded to a fixed `scale()`.
- Its value can be **null**. Division by zero does not throw and is not `"0"`; the SDK gives you
  `value() == null` with a human-readable `reason()`. Handle the null. In a series, a bucket that
  divided by zero is a null `value()` hole preserved in place; its `timestamp()` remains an
  `Instant`.

```java
DerivedValueResponse conversion = client.derived("conversion").value();
if (conversion.value() == null) {
    System.out.println("no value: " + conversion.reason()); // e.g. "division by zero"
} else {
    BigDecimal exact = new BigDecimal(conversion.value()); // never Double.parseDouble
}
```

## Errors: exactly three kinds

Every failure from this SDK is one of three unchecked exceptions, and the distinction tells you
what to do:

| Exception | Meaning | Typical handling |
|---|---|---|
| `CountersValidationException` | Rejected client-side (bad key, negative amount, over-cap metadata, bad bucket/window/mode) — **no request was made** | A bug in your code; fix the input |
| `CountersApiException` | The server answered with an HTTP error; `status()` is always the real status, `title()` the RFC 9457 problem title | Branch on `status()` (403 quota, 404 missing, 409 conflict…) |
| `CountersTransportException` | **No response was ever obtained** — network failure or timeout, retries exhausted; carries no status | Infrastructure problem; back off and retry later |

All three extend `CountersException` (itself a `RuntimeException`), so one
`catch (CountersException e)` catches anything originating in this SDK.
`onBatchError` likewise receives a `CountersException`, so asynchronous failures use the same typed
branches instead of exposing a raw `Throwable`.

Retries are built in: connect errors and HTTP 429/5xx retry with exponential backoff
(`maxRetries`, default 3), honouring `Retry-After`; `requestTimeoutMillis` bounds each attempt
(default 30s). Idempotency keys make retried writes safe.

## Builder reference

`CountersClient.builder()` — only `apiKey` is required:

| Option | Default | Meaning |
|---|---|---|
| `apiKey` | — | Full-access organization API key, sent as `Authorization: Bearer` |
| `baseUrl` | production | API endpoint override |
| `httpClient` | JDK default | Inject a custom `java.net.http.HttpClient` |
| `maxRetries` | 3 | Retries after the first attempt on connect errors and 429/5xx |
| `backoffMillis` | 200 | Base backoff, doubled per retry |
| `requestTimeoutMillis` | 30000 | Per-attempt request timeout |
| `batchEnabled` | true | Buffer + coalesce `add`/`subtract`; when false each write fires immediately |
| `maxBatchSize` | 100 | Distinct-counter count that triggers an early flush |
| `batchIntervalMillis` | 1000 | Background flush cadence; `<= 0` disables the timer |
| `onBatchError` | — | Sink for errors from fire-and-forget writes (background flushes and immediate mode) |

For a scoped publishable (`pk_`) token, use the separate transport-only builder. Its result exposes
only the operations that publishable tokens can perform:

```java
try (ReadOnlyCountersClient publicViews = CountersClient.publishableBuilder()
        .apiKey(System.getenv("COUNTERS_PUBLISHABLE_TOKEN"))
        .build()) {
    ValueResponse value = publicViews.counter("signups").value();
    Leaderboard leaders = publicViews.counter("raid-dps").leaderboard();
}
```

`ReadOnlyCountersClient` supports scoped counter values, counter/member series, leaderboards
(including windowed leaderboards), and member snapshots. Writes are not methods on its counter or
member handles, so attempting one is a compile error. Organization-wide `list`/`usage` reads and
derived counters require the full client and are absent too. The publishable builder retains the
transport options (`baseUrl`, `httpClient`, retries, backoff, and request timeout) but deliberately
has no batching options.

## Odds and ends

- **Usage**: `client.usage()` returns month-to-date operations, quota, reset instant, and counter
  headroom. The reset is `usage.operations().resetsAt()`—an `Instant`. Poll it periodically, not
  per write. Quota fields are null on unlimited plans.
- **Validation helpers**: `Validation.isValidCounterKey`, `isValidMemberKey`, `isValidMetadata`,
  and the `BUCKETS`/`WINDOWS`/`MODES` sets are public so you can pre-check user-supplied names.

## Development

```bash
# from java/ — needs a JDK 17+ and Gradle on PATH
gradle test
```

The behaviour is pinned by the shared vectors in [`../conformance/`](../conformance/) and the wire
contract by [`../openapi/openapi.yaml`](../openapi/); the suite replays both.
