# counters.dev SDKs

Official client libraries for [counters.dev](https://counters.dev) — an
**arbitrary-precision** counter API. Create a counter, add to it, read its value, roll it up into a
time series, rank members on a leaderboard, or derive one counter from others.

> ### 🚧 Pre-release — the packages are not published yet
>
> All three SDKs here are complete, tested, and release-ready, but **nothing has been pushed to npm,
> Maven Central or the Go module proxy yet**. The package names and install commands below are the
> ones that *will* be used; today they will not resolve. Until the first release, depend on an SDK
> from a checkout of this repository — each SDK's README shows how.

## Why arbitrary precision

Counter amounts and values are **decimal strings on the wire, never JSON numbers**. A JSON number is
an IEEE-754 double and silently loses precision above 2^53; counters.dev counters do not. Every SDK
carries that guarantee end to end, through its own request serialisation and response parsing, and
proves it in its test suite with values larger than a `u64`.

## The concepts, in one minute

- A **counter** is a named, signed, arbitrary-precision integer on the server: add, subtract, read,
  clear (which starts a new **epoch** — a season — keeping history), delete.
- A **leaderboard** is the same counter with **per-member sub-values**. Use a plain counter when
  only the total matters; use members when you care *who contributed*, ranked — per-player scores,
  per-tenant usage. If you would otherwise create one counter per user and sort client-side, you
  wanted a leaderboard. **Sum boards** accumulate deltas per member; **score boards**
  (`latest`/`min`/`max`) rank submitted scores. The first member write fixes the mode.
- A **series** is a counter's per-bucket delta over a time range (buckets `1m` … `1mo`) — the
  change in each bucket, not a running total. Each point exposes `timestamp` as the language's
  native time type and `value` as an arbitrary-precision decimal string; the compact JSON keys stay
  inside the transport layer.
- A **derived counter** is a server-defined, read-only **decimal** expression over other counters
  (e.g. a conversion rate). Its value is **null** on division by zero — data, not an error.
- Every SDK surfaces exactly **three error kinds**: *validation* (rejected client-side, no request
  made), *api* (the server answered with an HTTP error), and *transport* (no response was ever
  obtained). The same taxonomy, natively expressed in each language.

Each SDK's README develops this into a full mental model with runnable code in its own language.

## The SDKs

| Language | Package | Install |
|---|---|---|
| [TypeScript](./typescript) | npm `@counters.dev/sdk` | `npm install @counters.dev/sdk` |
| [Java](./java) | Maven `dev.counters:counters-sdk` | Gradle/Maven dependency |
| [Go](./go) | `github.com/counters-dot-dev/counters.dev-sdk/go` | `go get github.com/counters-dot-dev/counters.dev-sdk/go` |

Every SDK is **hand-written**, not generated. Each is a thin JSON/HTTP transport plus an ergonomic
layer built for its own language: client-side batching and coalescing, retries with idempotency keys,
confirmed vs. fire-and-forget writes, and typed errors. All three cover the same API surface and are
held to the same behaviour.

Publishable `pk_` tokens use a separate read-only construction path in every SDK. The resulting
client and handles expose only the counter reads that publishable tokens support, so trying to call a
write is a compile-time error rather than a deployed request that eventually receives HTTP 403.

## Three SDKs, on purpose

These are the three SDKs we use ourselves, in production, and can therefore vouch for. We ship a
client library for a language when we depend on it — so TypeScript, Java and Go get a hand-written
client, a full test suite, and the same release discipline as the service itself. A library nobody
on the team runs is a library nobody on the team can support, and we would rather ship three we
stand behind than a dozen we do not.

**Every other language talks to counters.dev over the REST API directly, and that is a first-class
way to use the product.** The API is small, fully documented, and the contract in
[`openapi/openapi.yaml`](./openapi) is the same one these three clients are built against — point
your language's HTTP client or an OpenAPI generator at it and you have everything the SDKs have. The
one rule that matters is the one the SDKs exist to enforce: **send and read amounts as decimal
strings, never as native numbers**, or you will silently lose precision above 2^53.

See the [API documentation](https://counters.dev) to get started.

## A taste (TypeScript)

```ts
import { CountersClient } from "@counters.dev/sdk";

const client = new CountersClient({ apiKey: process.env.COUNTERS_API_KEY! });
const signups = client.counter("signups");

signups.add(1);                                        // buffered, fire-and-forget
const state = await signups.addNow("18446744073709551616"); // confirmed — and larger than a u64
console.log(state.value);                              // a decimal string, always

const { value, epoch } = await signups.value();
const series = await signups.series({ from, to, bucket: "1h" });
for (const point of series.points) {
  console.log(point.timestamp.toISOString(), point.value);
}

await client.close();                                  // flush buffered writes before exit
```

Each SDK's README has the equivalent for its own language, and each has a runnable example app under
`<lang>/examples/e2e/` that exercises the entire public surface.

## How the SDKs are kept honest

- **[`openapi/openapi.yaml`](./openapi)** is the API contract — the *shape* source of truth. Because
  the clients are hand-written, CI runs a drift guard (`scripts/openapi-drift/check.mjs`) that diffs
  every SDK's operation inventory against the spec, so a client cannot silently fall behind it.
- **[`conformance/`](./conformance)** is the *behaviour* source of truth — language-agnostic vectors
  for key and amount validation, arbitrary-precision arithmetic, query encoding, response parsing, the
  error taxonomy, and full HTTP interactions. Every SDK asserts against the same files, so a
  divergence between any two implementations is caught mechanically.

Both directories are **synced read-only** from the counters.dev service repository, which is their
source of truth. See [`conformance/SYNCED.md`](./conformance/SYNCED.md) and
[`openapi/SYNCED.md`](./openapi/SYNCED.md).

## Links

- API documentation: <https://counters.dev>
- Contributing: [CONTRIBUTING.md](./CONTRIBUTING.md)
- Reporting a vulnerability: [SECURITY.md](./SECURITY.md)

## License

[Apache-2.0](./LICENSE).
