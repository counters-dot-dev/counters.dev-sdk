# Next.js App Router example

This sketch is the counter code behind a publishing app. [`app/actions.ts`](./app/actions.ts)
confirms a like before returning the exact new value to React, while
[`app/api/posts/[id]/views/route.ts`](./app/api/posts/%5Bid%5D/views/route.ts) reads hourly view deltas
and reshapes them for a sparkline. The comments contrast visible state, which needs a confirmed write,
with high-volume view telemetry, which a long-lived collector can buffer, flush, and report through
`batch.onError`.

[`app/public-views.ts`](./app/public-views.ts) is intentionally a client module. Its `pk_` token is
read-only and counter-scoped, so it may be embedded through a `NEXT_PUBLIC_*` variable and is passed
to `PublishableCountersClient`; that client's handles expose reads but no write methods. The writable
`COUNTERS_API_KEY` used by [`app/counters.ts`](./app/counters.ts) must remain server-only. A publishable
token does not make arbitrary counters public: reads outside its scope fail with HTTP 403.

Counter values and series deltas remain decimal strings all the way to the UI. In particular,
`"18446744073709551617"` is exact and even exceeds unsigned 64-bit, while
`Number("18446744073709551617")` silently rounds it because it is beyond
`Number.MAX_SAFE_INTEGER`; use the string for display or `BigInt` for integer arithmetic.
Series range bounds and bucket starts are native `Date` values, so the route serializes
`point.timestamp` explicitly while passing the exact delta through as `point.value`. The public
series option is `timeZone`; the SDK keeps the compact `tz` name inside its query encoder.

This is documentation that typechecks, not a runnable Next.js app. It uses the platform `Request` and
`Response` types plus a tiny local route-context type instead of depending on Next.js just for types.
After building the SDK in `typescript/`, run `npm ci && npm run typecheck` in this directory.
