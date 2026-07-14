# Derived-counter conformance vectors

`cases.json` pins the **client-side** derived-counter read surface. A derived counter is a server-defined expression over other
counters (e.g. a conversion ratio); the SDK only ever *reads* it — `GET /v1/derived/{key}/value` and
`GET /v1/derived/{key}/series`. There is **no** happy-path HTTP vector for it: a value needs a
*definition*, and definitions are created only on the dashboard/JWT plane the `conformance/http`
runners cannot reach (see `../http/README.md`). So the encoding and parsing contract is pinned here
instead, and the API implementation's own suite owns the numeric semantics.

`conformance/**` re-triggers every `sdk-*-ci.yml`, so adding a vector fans out to all suites with no
code change.

## The decimal contract (why this file exists)

The derived surface is **decimal, not integer**. `DecimalValue` (openapi.yaml) is a signed,
arbitrary-precision decimal **string** — distinct from `Value`, which is integer-only — and it is
`null` when the expression divided by zero. Two rules every SDK must honour, both pinned below:

1. **`value: null` is data, not an error.** SDKs surface it as a nullable/optional value alongside the
   `reason` string; they MUST NOT throw, and MUST NOT coerce it to `"0"`.
2. **Never parse the string to a float.** `"0.052337"` and the series points stay strings; a native
   `double` loses precision and the fixed-`scale` contract.

## Sections

### `encodeQuery` — derived series read params → query string
`GET /v1/derived/{key}/series` takes **only** `from`/`to`/`bucket`/`tz`. **Presence-exact**, same rule
as `series/cases.json`: every key listed under `query` present with that string value, every key not
listed absent. This surface has no `gapfill`, `mode`, `member`, or `groupBy`; each case's `absent`
array names the keys that MUST NOT appear (redundant with presence-exact, stated for loader authors).

### `parse` — response body → typed fields
`kind` selects the schema:

| `kind` | openapi schema | endpoint |
|---|---|---|
| `derivedValue` | `DerivedValueResponse` | `GET …/derived/{key}/value` |
| `derivedSeries` | `DerivedSeriesResponse` | `GET …/derived/{key}/series` |

`expect` lists exactly the asserted fields; wire fields absent from `expect` (e.g. `range`) are not
asserted. The `reasonAbsent: true` helper asserts a non-null value carries no `reason`. A `v: null`
point in `derived-series-null-mid-point` sits in the **middle** of the series (a per-bucket
division-by-zero hole) and must be preserved in place, not dropped or zero-filled.
