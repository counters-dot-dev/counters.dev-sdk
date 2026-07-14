import { describe, expect, it } from "vitest";
import { CountersClient } from "../src/client.js";
import type { DerivedSeriesParams } from "../src/types.js";
import { jsonResponse, loadVectors, mockFetch } from "./helpers.js";

// Derived-counter client-side vectors (conformance/derived/cases.json): series
// query encoding (ergonomic `timeZone` maps to wire `tz`; no gapfill/mode/member/groupBy), and
// decimal/null parsing for value + series. Public `value` maps from wire `v` and is a decimal STRING
// or null — never parsed to a native float.

interface QueryCase {
  name: string;
  params: { from: string; to: string; bucket: string; tz?: string };
  query: Record<string, string>;
  absent?: string[];
}
interface ParseCase {
  name: string;
  kind: "derivedValue" | "derivedSeries";
  body: Record<string, unknown>;
  expect: Record<string, unknown>;
}
const vectors = loadVectors<{ encodeQuery: QueryCase[]; parse: ParseCase[] }>("derived/cases.json");

const clientWith = (f: typeof fetch) => new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });

describe("derived conformance — encodeQuery (series read → query string)", () => {
  it.each(vectors.encodeQuery)("$name", async (c) => {
    let url!: URL;
    const client = clientWith(
      mockFetch((u) => {
        url = u;
        return jsonResponse(200, {
          key: "d",
          bucket: c.params.bucket,
          scale: 6,
          range: { from: c.params.from, to: c.params.to },
          points: [],
        });
      }),
    );
    const params: DerivedSeriesParams = {
      from: new Date(c.params.from),
      to: new Date(c.params.to),
      bucket: c.params.bucket as DerivedSeriesParams["bucket"],
      ...(c.params.tz === undefined ? {} : { timeZone: c.params.tz }),
    };
    await client.derived("conversion").series(params);

    const got = new Map<string, string>();
    url.searchParams.forEach((v, k) => got.set(k, v));
    // presence-exact: exactly the listed keys.
    expect(new Set(got.keys())).toEqual(new Set(Object.keys(c.query)));
    for (const [k, v] of Object.entries(c.query)) {
      const expected = k === "from" || k === "to" ? new Date(v).toISOString() : v;
      expect(got.get(k)).toBe(expected);
    }
    // and the explicitly-forbidden keys never appear.
    for (const k of c.absent ?? []) expect(got.has(k)).toBe(false);
  });
});

describe("derived conformance — parse (decimal / null response → typed fields)", () => {
  it.each(vectors.parse)("$name", async (c) => {
    const client = clientWith(mockFetch(() => jsonResponse(200, c.body)));

    if (c.kind === "derivedValue") {
      const r = await client.derived(c.body.key as string).value();
      const ex = c.expect as {
        key: string;
        value: string | null;
        scale: number;
        inputs: Record<string, string>;
        reason?: string;
        reasonAbsent?: boolean;
      };
      expect(r.key).toBe(ex.key);
      expect(r.scale).toBe(ex.scale);
      expect(r.inputs).toEqual(ex.inputs);
      if (ex.value === null) {
        expect(r.value).toBeNull();
      } else {
        // Decimal value stays a STRING (arbitrary precision; parsing to a float is banned).
        expect(typeof r.value).toBe("string");
        expect(r.value).toBe(ex.value);
      }
      if (ex.reasonAbsent) expect(r.reason).toBeUndefined();
      if (ex.reason !== undefined) expect(r.reason).toBe(ex.reason);
      return;
    }

    // derivedSeries
    const r = await client
      .derived(c.body.key as string)
      .series({
        from: new Date("2026-01-01T00:00:00Z"),
        to: new Date("2026-01-01T03:00:00Z"),
        bucket: "1h",
      });
    const ex = c.expect as {
      key: string;
      bucket: string;
      scale: number;
      points: { t: string; v: string | null }[];
    };
    expect(r.key).toBe(ex.key);
    expect(r.bucket).toBe(ex.bucket);
    expect(r.scale).toBe(ex.scale);
    const wireRange = c.body.range as { from: string; to: string };
    expect(r.range.from).toBeInstanceOf(Date);
    expect(r.range.from.toISOString()).toBe(new Date(wireRange.from).toISOString());
    expect(r.range.to).toBeInstanceOf(Date);
    expect(r.range.to.toISOString()).toBe(new Date(wireRange.to).toISOString());
    expect(r.points).toHaveLength(ex.points.length);
    r.points.forEach((pt, i) => {
      expect(pt.timestamp).toBeInstanceOf(Date);
      expect(pt.timestamp.toISOString()).toBe(new Date(ex.points[i]!.t).toISOString());
      if (ex.points[i]!.v === null) {
        // A mid-series division-by-zero hole is preserved in place as null, not dropped or zero-filled.
        expect(pt.value).toBeNull();
      } else {
        expect(typeof pt.value).toBe("string");
        expect(pt.value).toBe(ex.points[i]!.v);
      }
      expect(pt).not.toHaveProperty("t");
      expect(pt).not.toHaveProperty("v");
    });
  });

  it("maps optional wire tz to public timeZone without leaking the shorthand", async () => {
    const client = clientWith(
      mockFetch(() =>
        jsonResponse(200, {
          key: "conversion",
          bucket: "1d",
          tz: "Europe/London",
          scale: 6,
          range: { from: "2026-01-01T00:00:00Z", to: "2026-01-02T00:00:00Z" },
          points: [],
        }),
      ),
    );
    const result = await client.derived("conversion").series({
      from: new Date("2026-01-01T00:00:00Z"),
      to: new Date("2026-01-02T00:00:00Z"),
      bucket: "1d",
    });
    expect(result.timeZone).toBe("Europe/London");
    expect(result).not.toHaveProperty("tz");
  });
});
