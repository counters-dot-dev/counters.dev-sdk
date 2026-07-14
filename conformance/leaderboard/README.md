# Leaderboard conformance vectors

`cases.json` pins the **client-side** halves of the leaderboard/member surface — the parts that need no live board on the server: query encoding for
board reads, request-body encoding for member writes, and response parsing. The *stateful* behaviour
(ranking, competition-rank ties, seasons surviving a clear, min/max acceptance, windowed sums) is
pinned by the `scope: "http"` cases in `../http/cases.json`, replayed by the server-side runners.
Splitting it this way lets a per-language conformance loader be written from
this file alone, before that SDK can reach a real board.

`conformance/**` re-triggers every `sdk-*-ci.yml`, so adding a vector fans out to all suites with no
code change.

## Sections

### `encodeQuery` — board read params → query string
`GET /v1/counters/{key}/leaderboard`. **Presence-exact**, identical to `series/cases.json`: every key
listed under a case's `query` must appear with that exact string value, and **every key not listed
must be absent**. Integers (`limit`/`offset`/`epoch`) render without separators; `order` is `asc` or
`desc`; `window` is one of the fixed enum `1h|6h|12h|1d|7d|30d` and, when set, selects the
`WindowLeaderboard` response.

`window-invalid-value-local-reject` is an **error case**: it carries `expect.taxonomy` (value
`validation`) and **no** `query`. An SDK exposing the windowed read validates `window` against the
enum client-side and raises its `validation`-taxonomy error (see `../errors`) before issuing a
request. (A read-only SDK that does not expose windowed reads structurally skips this case.)

### `encodeBody` — member write inputs → request JSON body
`op` names the write (`memberAdd`, `memberSubtract`, `memberSubmit`); `input` is the SDK-level call
arguments; `body` is the JSON object the SDK must send. **Presence-exact on the object** (every key
in `body` present, none extra — this pins `metadata`/`occurredAt` omitted-when-unset and `mode`
omitted-when-unset), and **byte-verbatim on the values**: `amount`/`value` are arbitrary-precision
strings copied through unchanged (never reformatted or parsed to a number), and `metadata` is opaque.
Key ordering is not asserted (it is not portable across JSON encoders); the object identity is.

### `parse` — response body → typed fields
Each case's `kind` selects the schema:

| `kind` | openapi schema | endpoint |
|---|---|---|
| `leaderboard` | `Leaderboard` | `GET …/leaderboard` |
| `windowLeaderboard` | `WindowLeaderboard` | `GET …/leaderboard?window=` |
| `memberValue` | `MemberValue` | member add/subtract/submit response |
| `memberSnapshot` | `MemberSnapshot` | `GET …/members/{member}` |
| `memberRemoved` | `MemberRemoved` | `DELETE …/members/{member}` |

`expect` lists exactly the fields a loader asserts; fields on the wire body but absent from `expect`
(e.g. `limit`, `offset`, `updatedAt`) are not asserted. Two expectation helpers assert a field is
**absent** from the parsed value:

- `totalAbsent: true` — the board carries no `total` (only sum boards do; `leaderboard-min-without-total`).
- `valueAbsent: true` — no board-total `value` on this member body (`member-value-rejected`).

**Numbers are strings.** `value`, `total`, `memberValue`, and `percentile` are decimal/integer
strings on the wire and MUST stay strings through parsing — the values are arbitrary precision and a
scale-2 `percentile` like `"83.33"` (or a leader's `"100.00"`) loses its contract if parsed to a
float. `leaderboard-sum-with-total` deliberately carries an entry value and a `total` that overflow
i64 to catch a native-int parse. Typed SDKs assert field access; untyped read-path SDKs may assert
against the raw map.
