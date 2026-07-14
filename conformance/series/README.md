# Series conformance vectors

`cases.json` pins the two language-agnostic halves of the `series` read path:

- **`query`** — series parameters → the exact set of URL query parameters the SDK must send. The
  contract is *presence-exact*: every key listed under a case's `query` must appear with that string
  value, and **every key not listed must be absent**. This is how three rules are pinned mechanically:
  - `gapfill` is encoded as the string `"true"` when true and **omitted entirely** when false
    (server default is false — sending `gapfill=false` would be redundant and is treated as a bug).
  - optional `tz` and `mode` are passed through verbatim when set and omitted when unset. In
    particular `mode` must reach the wire when the caller sets it. An SDK that does not expose `mode`
    cannot express the two mode-setting cases and skips exactly those, each marked
    `structurally skipped: series 'mode' not exposed`. Rewriting a case's expected `query` to pass
    (dropping keys) defeats the point of the vector and is banned.
  - `from`/`to` are given here as already-formatted RFC-3339 strings so the mapping is
    language-neutral; each SDK separately formats native date/time inputs to this same shape (tested
    in that SDK's unit suite, not here).

  `bucket` is assumed already validated against `buckets.json` before the query is built.

  Two **dimensional** query cases ride the same schema:
  `member-param-passthrough` (`series?member=`) and `groupby-member-passthrough`
  (`series?groupBy=member`) pass their extra parameter through verbatim under the same presence-exact
  rule. A third case, `member-and-groupby-locally-exclusive`, carries **`expect.taxonomy` instead of
  `query`**: `member` and `groupBy` are mutually exclusive (the server answers 400), so an SDK that
  exposes both must reject the combination client-side — the `validation` taxonomy type
  (`conformance/errors`) — *before* issuing any request. A query case is an error case iff it has
  `expect.taxonomy` and no `query`. SDKs that expose neither `member` nor `groupBy` structurally skip
  all three, exactly as the `mode` cases are skipped.

- **`parse`** — a response body → the parsed points. The wire keys `t` (bucket start, RFC-3339) and
  `v` (delta, an arbitrary-precision **decimal string** — never a native int; see the `bignum-point`
  case) are preserved verbatim by every SDK. An SDK with an untyped read path may assert against the
  raw map; typed SDKs assert field access.

  A parse case's `kind` selects which response type it exercises; **a case with no `kind` is the
  plain `SeriesResponse`** (the original three cases). The dimensional additions are:
  - `kind: "memberSeries"` — a `MemberSeriesResponse` (`series?member=`): the plain series shape plus
    a top-level `member`.
  - `kind: "memberGroupSeries"` — a `MemberGroupSeriesResponse` (`series?groupBy=member`): a `series`
    array of `{member, points}`, with **no** top-level `mode`.
  Every case's `expect` lists exactly the fields a loader asserts; fields present on the wire body but
  absent from `expect` (e.g. `range`) are not asserted.

Loaders live in each SDK's test suite; `conformance/**` re-triggers every `sdk-*-ci.yml`, so editing
a vector fans out to all suites with no code change.
