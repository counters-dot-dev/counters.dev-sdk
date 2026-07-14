import { describe, expect, it, vi } from "vitest";
import { CountersClient } from "../src/client.js";
import { CountersValidationError } from "../src/errors.js";
import type { Window } from "../src/types.js";
import { jsonResponse, loadVectors, mockFetch } from "./helpers.js";

// Leaderboard/member client-side vectors (conformance/leaderboard/cases.json):
// board-read query encoding (presence-exact), member-write body encoding (presence-exact +
// byte-verbatim values), and response parsing across five schemas. Numbers on the wire (value,
// total, memberValue, percentile) MUST stay strings — never parsed to a native number.

interface EncodeQueryCase {
  name: string;
  params: Record<string, unknown>;
  query?: Record<string, string>;
  expect?: { taxonomy?: string };
}
interface EncodeBodyCase {
  name: string;
  op: "memberAdd" | "memberSubtract" | "memberSubmit";
  input: Record<string, string>;
  body: Record<string, string>;
}
interface ParseCase {
  name: string;
  kind: "leaderboard" | "windowLeaderboard" | "memberValue" | "memberSnapshot" | "memberRemoved";
  body: Record<string, unknown>;
  expect: Record<string, unknown>;
}
const vectors = loadVectors<{
  encodeQuery: EncodeQueryCase[];
  encodeBody: EncodeBodyCase[];
  parse: ParseCase[];
}>("leaderboard/cases.json");

const clientWith = (f: typeof fetch) => new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });

describe("leaderboard conformance — encodeQuery (board read → query string)", () => {
  it.each(vectors.encodeQuery)("$name", async (c) => {
    // An error case (window-invalid-value-local-reject) carries expect.taxonomy and no query:
    // the SDK validates the window enum client-side and raises BEFORE any request.
    if (c.expect?.taxonomy === "validation") {
      const fetchFn = vi.fn(() => jsonResponse(200, {}));
      const client = clientWith(fetchFn as unknown as typeof fetch);
      // The window enum is validated synchronously before any request is issued.
      expect(() => client.counter("k").leaderboard(c.params as { window: Window })).toThrow(
        CountersValidationError,
      );
      expect(fetchFn).not.toHaveBeenCalled();
      return;
    }

    let url!: URL;
    const client = clientWith(
      mockFetch((u) => {
        url = u;
        return jsonResponse(200, leaderboardStub(c.params));
      }),
    );
    await client.counter("k").leaderboard(c.params as { window?: Window });

    const got = new Map<string, string>();
    url.searchParams.forEach((v, k) => got.set(k, v));
    expect(new Set(got.keys())).toEqual(new Set(Object.keys(c.query!)));
    for (const [k, v] of Object.entries(c.query!)) expect(got.get(k)).toBe(v);
  });
});

describe("leaderboard conformance — encodeBody (member write → request JSON)", () => {
  it.each(vectors.encodeBody)("$name", async (c) => {
    let sent!: string;
    const client = clientWith(
      mockFetch((_u, init) => {
        sent = init.body as string;
        return jsonResponse(200, {
          key: "k",
          member: "m",
          memberValue: "0",
          memberAccepted: true,
          mode: "sum",
          epoch: 0,
        });
      }),
    );
    const m = client.counter("k").member("m");
    const opts = optsFrom(c.input);
    if (c.op === "memberAdd") await m.add(c.input.amount!, opts);
    else if (c.op === "memberSubtract") await m.subtract(c.input.amount!, opts);
    else await m.submit(c.input.value!, { ...opts, mode: c.input.mode as never });

    const parsed = JSON.parse(sent) as Record<string, string>;
    // presence-exact on the object: exactly the keys the vector names.
    expect(new Set(Object.keys(parsed))).toEqual(new Set(Object.keys(c.body)));
    // byte-verbatim on the values (amount/value/metadata copied through unchanged).
    for (const [k, v] of Object.entries(c.body)) {
      const expected = k === "occurredAt" ? new Date(v).toISOString() : v;
      expect(parsed[k]).toBe(expected);
    }
  });
});

describe("leaderboard conformance — parse (response → typed fields)", () => {
  it.each(vectors.parse)("$name", async (c) => {
    const client = clientWith(mockFetch(() => jsonResponse(200, c.body)));
    const counter = client.counter(c.body.key as string);

    let result: Record<string, unknown>;
    switch (c.kind) {
      case "leaderboard":
        result = (await counter.leaderboard()) as unknown as Record<string, unknown>;
        break;
      case "windowLeaderboard":
        result = (await counter.leaderboard({ window: c.body.window as Window })) as unknown as Record<string, unknown>;
        break;
      case "memberValue":
        result = (await counter.member(c.body.member as string).add("0")) as unknown as Record<string, unknown>;
        break;
      case "memberSnapshot":
        result = (await counter.member(c.body.member as string).get()) as unknown as Record<string, unknown>;
        break;
      case "memberRemoved":
        result = (await counter.member(c.body.member as string).remove()) as unknown as Record<string, unknown>;
        break;
    }
    assertFields(result, c.expect);
    assertNativeTimestamps(result, c);
  });

  it("uses the requested all-time variant when the response carries a stray window field", async () => {
    const client = clientWith(
      mockFetch(() =>
        jsonResponse(200, {
          key: "scores",
          mode: "max",
          epoch: 0,
          order: "desc",
          memberCount: 1,
          limit: 100,
          offset: 0,
          window: null,
          entries: [
            {
              rank: 1,
              member: "alice",
              value: "10",
              updatedAt: "2026-01-01T00:00:00Z",
            },
          ],
        }),
      ),
    );

    const result = await client.counter("scores").leaderboard();
    expect(result.entries[0]?.updatedAt).toBeInstanceOf(Date);
    expect(result.entries[0]?.updatedAt.toISOString()).toBe("2026-01-01T00:00:00.000Z");
    await client.close();
  });
});

// ── helpers ─────────────────────────────────────────────────────────────────────────────────────

const STRING_FIELDS = new Set(["value", "total", "memberValue", "percentile"]);

function assertNativeTimestamps(actual: Record<string, unknown>, vector: ParseCase): void {
  if (vector.kind === "leaderboard") {
    const actualEntries = actual.entries as Record<string, unknown>[];
    const wireEntries = vector.body.entries as Record<string, unknown>[];
    actualEntries.forEach((entry, i) => {
      const updatedAt = entry.updatedAt;
      expect(updatedAt, `entry ${i} updatedAt must be a Date`).toBeInstanceOf(Date);
      expect((updatedAt as Date).toISOString()).toBe(
        new Date(wireEntries[i]!.updatedAt as string).toISOString(),
      );
    });
  }
  if (vector.kind === "windowLeaderboard") {
    expect(actual.effectiveStart, "window effectiveStart must be a Date").toBeInstanceOf(Date);
    expect((actual.effectiveStart as Date).toISOString()).toBe(
      new Date(vector.body.effectiveStart as string).toISOString(),
    );
    expect(actual.effectiveEnd, "window effectiveEnd must be a Date").toBeInstanceOf(Date);
    expect((actual.effectiveEnd as Date).toISOString()).toBe(
      new Date(vector.body.effectiveEnd as string).toISOString(),
    );
    const entries = actual.entries as Record<string, unknown>[];
    entries.forEach((entry, i) => {
      expect(entry, `window entry ${i} must not gain updatedAt`).not.toHaveProperty("updatedAt");
    });
  }
  if (vector.kind === "memberSnapshot") {
    expect(actual.updatedAt, "member snapshot updatedAt must be a Date").toBeInstanceOf(Date);
    expect((actual.updatedAt as Date).toISOString()).toBe(
      new Date(vector.body.updatedAt as string).toISOString(),
    );
  }
}

function assertFields(actual: Record<string, unknown>, expected: Record<string, unknown>): void {
  for (const [k, v] of Object.entries(expected)) {
    if (k === "totalAbsent") {
      expect(actual.total, "total should be absent").toBeUndefined();
      continue;
    }
    if (k === "valueAbsent") {
      expect(actual.value, "value should be absent").toBeUndefined();
      continue;
    }
    if (k === "entries") {
      const wantEntries = v as Record<string, unknown>[];
      const gotEntries = actual.entries as Record<string, unknown>[];
      expect(gotEntries).toHaveLength(wantEntries.length);
      wantEntries.forEach((we, i) => assertFields(gotEntries[i]!, we));
      continue;
    }
    if (k === "effectiveStart" || k === "effectiveEnd") {
      expect(actual[k], `${k} must be a Date`).toBeInstanceOf(Date);
      expect((actual[k] as Date).toISOString()).toBe(new Date(v as string).toISOString());
      continue;
    }
    if (STRING_FIELDS.has(k)) {
      // Arbitrary-precision decimal/integer strings must survive parsing AS strings (never a number).
      expect(typeof actual[k], `${k} must stay a string`).toBe("string");
    }
    expect(actual[k], `field ${k}`).toBe(v);
  }
}

function optsFrom(input: Record<string, string>): { metadata?: string; occurredAt?: Date } {
  const opts: { metadata?: string; occurredAt?: Date } = {};
  if (input.metadata !== undefined) opts.metadata = input.metadata;
  if (input.occurredAt !== undefined) opts.occurredAt = new Date(input.occurredAt);
  return opts;
}

function leaderboardStub(params: Record<string, unknown>): Record<string, unknown> {
  return params.window !== undefined
    ? {
        key: "k",
        mode: "sum",
        window: params.window,
        order: "desc",
        total: "0",
        memberCount: 0,
        limit: 100,
        offset: 0,
        effectiveStart: "2026-01-01T00:00:00Z",
        effectiveEnd: "2026-01-01T01:00:00Z",
        entries: [],
      }
    : { key: "k", mode: "sum", epoch: 0, order: "desc", memberCount: 0, limit: 100, offset: 0, entries: [] };
}
