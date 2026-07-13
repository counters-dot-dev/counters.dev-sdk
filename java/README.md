# Counters Java SDK

Official Java SDK for [counters.dev](https://counters.dev) — the multi-tenant **arbitrary-precision** counter
service. Pure JDK 17 standard library, zero runtime dependencies.

## Install

> **Not yet published.** Maven Central coordinates will be `dev.counters:counters-sdk:0.1.0`. Until then,
> build the jar locally (`gradle build` → `build/libs/counters-sdk-0.1.0.jar`) or consume this directory as a
> Gradle [composite build](https://docs.gradle.org/current/userguide/composite_builds.html).

```kotlin
// build.gradle.kts — once published
dependencies {
    implementation("dev.counters:counters-sdk:0.1.0")
}
```

## Quickstart

```java
import dev.counters.sdk.*;
import java.time.OffsetDateTime;

try (CountersClient client = CountersClient.builder()
        .apiKey(System.getenv("COUNTERS_API_KEY"))
        .build()) {

    CounterHandle registrations = client.counter("registrations");

    // Buffered: coalesced per counter, flushed in the background (default: every 1s or at 100 counters).
    registrations.add(1);
    registrations.subtract(1);

    // Immediate: applies now and returns the new state. Values are arbitrary-precision strings.
    Counter c = registrations.addNow("100000000000000000000000000000000");
    System.out.println(c.value()); // exact — parse with new BigInteger(c.value()) if you need math

    // Reads.
    ValueResponse v = registrations.value();
    SeriesResponse s = registrations.series(new SeriesParams(
            OffsetDateTime.now().minusDays(1), OffsetDateTime.now(), "1h"));

    Usage usage = client.usage();

    MemberHandle alice = registrations.member("alice");
    alice.add(new BigInteger("170141183460469231731687303715884105728"),
            new MemberWriteOptions("room1:500", OffsetDateTime.now()));
    Leaderboard board = registrations.leaderboard();
    System.out.println(board.entries().get(0).value()); // exact string, even past u64

    DerivedValueResponse conversion = client.derived("conversion").value();
    if (conversion.value() == null) {
        System.out.println(conversion.reason()); // e.g. division by zero
    }
} // close() flushes buffered writes and stops the background timer
```

## API summary

| Call | Effect |
|------|--------|
| `CountersClient.builder()` | `apiKey` (required), `baseUrl`, `maxRetries` (3), `backoffMillis` (200), `batchEnabled` (true), `maxBatchSize` (100), `batchIntervalMillis` (1000), `onBatchError`, `httpClient` (injectable) |
| `client.counter(key)` | Validated handle (throws `CountersValidationException` on a bad key) |
| `handle.add / subtract` | Buffered write, coalesced per counter into one net op per flush |
| `handle.addNow / subtractNow` | Immediate write; optional `OffsetDateTime occurredAt` buckets the op at event time; returns `Counter` |
| `handle.clear()` | Reset to zero (new epoch; history retained) |
| `handle.delete()` | Tombstone the counter |
| `handle.value()` | Current value (`String`, arbitrary precision) |
| `handle.series(SeriesParams)` | Delta per bucket; bucket ∈ `1m 5m 1h 1d 1w 1mo`, optional `mode`, `tz`, `gapfill` |
| `handle.memberSeries(member, SeriesParams)` | One member's delta series; requires member series enabled on the counter |
| `handle.groupSeries(SeriesParams)` | Dense per-member multi-series; requires member series enabled on the counter |
| `handle.leaderboard()` / `handle.leaderboard(LeaderboardParams)` | Ranked member leaderboard |
| `handle.windowLeaderboard(WindowLeaderboardParams)` | Trailing-window leaderboard; local window validation |
| `handle.member(member)` | Member handle with immediate `get`, `remove`, `add`, `subtract`, `submit` |
| `client.usage()` | Organization quota/usage state |
| `client.derived(key).value()` / `.series(DerivedSeriesParams)` | Read-only derived counter value/series; decimal strings, nullable value with reason |
| `client.list(cursor, limit)` | Page through counters |
| `client.flush()` / `client.close()` | Drain the buffer now / drain + stop the timer (also via try-with-resources) |

## Semantics worth knowing

- **Arbitrary precision, always.** Amounts accept `long`, decimal `String`, or `BigInteger`; values travel as
  JSON strings. Leaderboard totals/entries and derived decimal values also stay strings. Nothing is ever
  routed through a `double`.
- **Retries are safe.** Connect errors and HTTP 429/500/502/503/504 are retried with exponential backoff
  (`backoffMillis * 2^attempt`); every write carries an `Idempotency-Key` (random v4 UUID) that is reused
  across retries, so the server de-duplicates. Terminal failures throw `CountersApiException(status, title)`.
- **Member writes are immediate.** `counter.member("m").add/subtract/submit/remove` send one request now
  with a fresh idempotency key; they are intentionally separate from buffered counter writes.
- **Validation is local where the contract is local.** Counter keys, member keys, metadata byte length
  (1024 UTF-8 bytes), series buckets, and window values are rejected before network I/O.
- **Derived nulls are data.** A divide-by-zero derived value is `null` with `reason()`, not an exception and
  not coerced to `"0"`.
- **Buffering loses no meaningful detail.** The finest series bucket is one minute, so summing add/subtract
  per counter between flushes is invisible in the data — and net-zero ops are dropped entirely.
- **The batcher thread is a daemon.** It will never keep your JVM alive, which also means you must `close()`
  (or `flush()`) before exit to push out the last buffered writes.

## Development

```bash
# from java/ — needs a JDK 17+ and Gradle on PATH
gradle test
```

Validation and bignum behaviour are pinned by the shared vectors in [`../conformance/`](../conformance/),
and the wire contract by [`../openapi/openapi.yaml`](../openapi/openapi.yaml).
