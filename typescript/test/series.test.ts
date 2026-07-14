import { describe, expect, it, vi } from "vitest";
import { CountersClient } from "../src/client.js";
import { CountersValidationError } from "../src/errors.js";
import type {
  MemberGroupSeriesResponse,
  MemberSeriesResponse,
  SeriesParams,
  SeriesPoint,
  SeriesResponse,
} from "../src/types.js";
import { jsonResponse, loadVectors, mockFetch } from "./helpers.js";

// B8/B9 + dimensional member series (conformance/series/cases.json): series params -> exact query
// encoding (presence-exact), member/groupBy passthrough, the mutually-exclusive local reject, and
// SeriesResponse / MemberSeriesResponse / MemberGroupSeriesResponse parse via typed field access.
interface QueryCase {
  name: string;
  params: Record<string, unknown>;
  query?: Record<string, string>;
  expect?: { taxonomy?: string };
}
interface Point {
  t: string;
  v: string;
}
interface ParseCase {
  name: string;
  kind?: "memberSeries" | "memberGroupSeries";
  body: Record<string, unknown>;
  expect: Record<string, unknown>;
}
const vectors = loadVectors<{ query: QueryCase[]; parse: ParseCase[] }>("series/cases.json");

// Series params for the dynamic vector params (member/groupBy widen the base params).
type AnySeriesParams = SeriesParams & { member?: string; groupBy?: "member" };

describe("series conformance (conformance/series)", () => {
  it.each(vectors.query)("query: $name", async (c) => {
    const params = {
      ...c.params,
      from: new Date(c.params.from as string),
      to: new Date(c.params.to as string),
      timeZone: c.params.tz as string | undefined,
    };
    delete (params as { tz?: unknown }).tz;
    // An error case carries `expect.taxonomy` and NO `query`: the encoding must raise a validation
    // error BEFORE any request is sent (member + groupBy set together).
    if (c.expect?.taxonomy === "validation") {
      const fetchFn = vi.fn(() => jsonResponse(200, {}));
      const client = new CountersClient({
        apiKey: "k",
        fetch: fetchFn as unknown as typeof fetch,
        baseUrl: "https://x/v1",
      });
      // Local validation throws synchronously (before the request promise is created — same contract
      // as assertBucket / a bad counter key), so no request is ever issued.
      expect(() => client.counter("c").series(params as AnySeriesParams)).toThrow(
        CountersValidationError,
      );
      expect(fetchFn).not.toHaveBeenCalled();
      return;
    }

    let url!: URL;
    const f = mockFetch((u) => {
      url = u;
      return jsonResponse(200, {
        counterKey: "c",
        bucket: "1h",
        mode: "delta",
        range: { from: params.from.toISOString(), to: params.to.toISOString() },
        points: [],
      });
    });
    const client = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    await client.counter("c").series(params as AnySeriesParams);

    // presence-exact: every listed key present with that value, and nothing else on the wire.
    const got = new Map<string, string>();
    url.searchParams.forEach((v, k) => got.set(k, v));
    expect(new Set(got.keys())).toEqual(new Set(Object.keys(c.query!)));
    for (const [k, v] of Object.entries(c.query!)) {
      const expected = k === "from" || k === "to" ? new Date(v).toISOString() : v;
      expect(got.get(k)).toBe(expected);
    }
  });

  it.each(vectors.parse)("parse: $name", async (c) => {
    const f = mockFetch(() => jsonResponse(200, c.body));
    const client = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    const range = c.body.range as { from: string; to: string };
    const bucket = c.body.bucket as SeriesParams["bucket"];
    const base = { from: new Date(range.from), to: new Date(range.to), bucket };

    if (c.kind === "memberGroupSeries") {
      const r = (await client
        .counter(c.body.counterKey as string)
        .series({ ...base, groupBy: "member" })) as MemberGroupSeriesResponse;
      const ex = c.expect as { counterKey: string; bucket: string; series: { member: string; points: Point[] }[] };
      expect(r.counterKey).toBe(ex.counterKey);
      expect(r.bucket).toBe(ex.bucket);
      // No top-level mode on a group series (openapi MemberGroupSeriesResponse).
      expect((r as unknown as { mode?: unknown }).mode).toBeUndefined();
      assertRange(r.range, range);
      expect(r.series).toHaveLength(ex.series.length);
      r.series.forEach((s, i) => {
        expect(s.member).toBe(ex.series[i]!.member);
        assertPoints(s.points, ex.series[i]!.points);
      });
      return;
    }

    if (c.kind === "memberSeries") {
      const r = (await client
        .counter(c.body.counterKey as string)
        .series({ ...base, member: c.body.member as string })) as MemberSeriesResponse;
      const ex = c.expect as { counterKey: string; member: string; bucket: string; mode: string; points: Point[] };
      expect(r.counterKey).toBe(ex.counterKey);
      expect(r.member).toBe(ex.member);
      expect(r.bucket).toBe(ex.bucket);
      expect(r.mode).toBe(ex.mode);
      assertRange(r.range, range);
      assertPoints(r.points, ex.points);
      return;
    }

    const r = (await client.counter(c.body.counterKey as string).series(base)) as SeriesResponse;
    const ex = c.expect as { counterKey: string; bucket: string; mode: string; points: Point[] };
    expect(r.counterKey).toBe(ex.counterKey);
    expect(r.bucket).toBe(ex.bucket);
    expect(r.mode).toBe(ex.mode);
    assertRange(r.range, range);
    assertPoints(r.points, ex.points);
  });
});

function assertPoints(actual: SeriesPoint[], expected: Point[]): void {
  expect(actual).toHaveLength(expected.length);
  actual.forEach((pt, i) => {
    expect(pt.timestamp).toBeInstanceOf(Date);
    expect(pt.timestamp.toISOString()).toBe(new Date(expected[i]!.t).toISOString());
    // Delta stays a string (arbitrary precision; never a JS number).
    expect(typeof pt.value).toBe("string");
    expect(pt.value).toBe(expected[i]!.v);
    // The compact wire keys do not leak into the ergonomic public object.
    expect(pt).not.toHaveProperty("t");
    expect(pt).not.toHaveProperty("v");
  });
}

function assertRange(actual: { from: Date; to: Date }, expected: { from: string; to: string }): void {
  expect(actual.from).toBeInstanceOf(Date);
  expect(actual.to).toBeInstanceOf(Date);
  expect(actual.from.toISOString()).toBe(new Date(expected.from).toISOString());
  expect(actual.to.toISOString()).toBe(new Date(expected.to).toISOString());
}
