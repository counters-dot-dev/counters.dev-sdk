import { describe, expect, it, vi } from "vitest";
import { CountersClient } from "../src/client.js";
import { CountersValidationError } from "../src/errors.js";
import { jsonResponse, mockFetch } from "./helpers.js";

// Member handle loopback: correct HTTP method/path/idempotency per op, local validation before any
// request, and the arbitrary-precision guarantee through the member write/read path.

const memberValueBody = {
  key: "lb",
  member: "alice",
  memberValue: "10",
  memberAccepted: true,
  mode: "sum",
  epoch: 0,
  value: "10",
};

function capture(responder: (url: URL, init: RequestInit) => Response) {
  const seen: { method?: string; path?: string; idem?: string; body?: string } = {};
  const client = new CountersClient({
    apiKey: "k",
    baseUrl: "https://x/v1",
    fetch: mockFetch((url, init) => {
      seen.method = init.method;
      seen.path = url.pathname;
      seen.idem = (init.headers as Record<string, string>)?.["idempotency-key"];
      seen.body = init.body as string | undefined;
      return responder(url, init);
    }),
  });
  return { client, seen };
}

describe("MemberHandle — HTTP wiring", () => {
  it("add POSTs to …/members/{member}/add with an idempotency key", async () => {
    const { client, seen } = capture(() => jsonResponse(200, memberValueBody));
    const r = await client.counter("lb").member("alice").add(10);
    expect(seen.method).toBe("POST");
    expect(seen.path).toBe("/v1/counters/lb/members/alice/add");
    expect(seen.idem).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(seen.body!)).toEqual({ amount: "10" });
    expect(r.memberValue).toBe("10");
    expect(r.memberAccepted).toBe(true);
  });

  it("subtract POSTs to …/members/{member}/subtract", async () => {
    const { client, seen } = capture(() => jsonResponse(200, memberValueBody));
    await client.counter("lb").member("alice").subtract("3");
    expect(seen.method).toBe("POST");
    expect(seen.path).toBe("/v1/counters/lb/members/alice/subtract");
    expect(JSON.parse(seen.body!)).toEqual({ amount: "3" });
  });

  it("submit POSTs value + mode + metadata + occurredAt to …/members/{member}/submit", async () => {
    const { client, seen } = capture(() =>
      jsonResponse(200, { ...memberValueBody, mode: "max", memberValue: "1417" }),
    );
    await client
      .counter("raid")
      .member("alice|bob")
      .submit("1417", {
        mode: "max",
        metadata: "room1:400",
        occurredAt: new Date("2026-01-01T00:00:00Z"),
      });
    expect(seen.method).toBe("POST");
    // The `|` in the member key is percent-encoded in the path.
    expect(seen.path).toBe("/v1/counters/raid/members/alice%7Cbob/submit");
    expect(JSON.parse(seen.body!)).toEqual({
      value: "1417",
      mode: "max",
      metadata: "room1:400",
      occurredAt: "2026-01-01T00:00:00.000Z",
    });
  });

  it("get GETs …/members/{member} with epoch/order query and no idempotency key", async () => {
    let url!: URL;
    let idem: string | undefined;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((u, init) => {
        url = u;
        idem = (init.headers as Record<string, string>)?.["idempotency-key"];
        return jsonResponse(200, {
          key: "snap",
          member: "alice",
          value: "10",
          rank: 2,
          percentile: "83.33",
          memberCount: 6,
          mode: "sum",
          epoch: 0,
          updatedAt: "2026-01-01T00:00:00Z",
        });
      }),
    });
    const snap = await client.counter("snap").member("alice").get({ epoch: 0, order: "desc" });
    expect(url.pathname).toBe("/v1/counters/snap/members/alice");
    expect(url.searchParams.get("epoch")).toBe("0");
    expect(url.searchParams.get("order")).toBe("desc");
    expect(idem).toBeUndefined();
    expect(snap.percentile).toBe("83.33");
    expect(typeof snap.percentile).toBe("string");
    expect(snap.updatedAt).toBeInstanceOf(Date);
    expect(snap.updatedAt.toISOString()).toBe("2026-01-01T00:00:00.000Z");
  });

  it("remove DELETEs …/members/{member} with an idempotency key", async () => {
    const { client, seen } = capture(() =>
      jsonResponse(200, { key: "rm", member: "alice", epoch: 0, value: "15" }),
    );
    const r = await client.counter("rm").member("alice").remove();
    expect(seen.method).toBe("DELETE");
    expect(seen.path).toBe("/v1/counters/rm/members/alice");
    expect(seen.idem).toMatch(/^[0-9a-f-]{36}$/);
    expect(r.value).toBe("15");
  });
});

describe("MemberHandle — client-side validation (no request issued)", () => {
  it("rejects an invalid member key before any request", () => {
    const fetchFn = vi.fn(() => jsonResponse(200, {}));
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: fetchFn as unknown as typeof fetch,
    });
    expect(() => client.counter("lb").member("has space")).toThrow(CountersValidationError);
    expect(fetchFn).not.toHaveBeenCalled();
  });

  it("rejects over-cap metadata before any request (byte cap, not char cap)", () => {
    const fetchFn = vi.fn(() => jsonResponse(200, memberValueBody));
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: fetchFn as unknown as typeof fetch,
    });
    // 342 × '€' = 1026 UTF-8 bytes (> 1024) though only 342 characters. Validated synchronously.
    expect(() => client.counter("lb").member("alice").add(1, { metadata: "€".repeat(342) })).toThrow(
      CountersValidationError,
    );
    expect(fetchFn).not.toHaveBeenCalled();
  });

  it("rejects a negative member add amount before any request", () => {
    const fetchFn = vi.fn(() => jsonResponse(200, memberValueBody));
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: fetchFn as unknown as typeof fetch,
    });
    expect(() => client.counter("lb").member("alice").add(-1)).toThrow(CountersValidationError);
    expect(fetchFn).not.toHaveBeenCalled();
  });
});

describe("MemberHandle — arbitrary precision (bignum leg)", () => {
  const OVER_U64 = "170141183460469231731687303715884105728"; // 2^127, far beyond u64

  it("add serializes a >u64 amount as the exact digit string and parses a >u64 result exactly", async () => {
    let sent!: string;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((_u, init) => {
        sent = init.body as string;
        return jsonResponse(200, {
          key: "lb",
          member: "bob",
          memberValue: OVER_U64,
          memberAccepted: true,
          mode: "sum",
          epoch: 0,
          value: OVER_U64,
        });
      }),
    });
    const r = await client.counter("lb").member("bob").add(OVER_U64);
    expect(sent).toContain(`"amount":"${OVER_U64}"`);
    expect(JSON.parse(sent).amount).toBe(OVER_U64);
    expect(r.memberValue).toBe(OVER_U64);
    expect(typeof r.memberValue).toBe("string");
    expect(r.value).toBe(OVER_U64);
  });

  it("submit accepts a signed value bigint and sends it verbatim", async () => {
    let sent!: string;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((_u, init) => {
        sent = init.body as string;
        return jsonResponse(200, {
          key: "s",
          member: "z",
          memberValue: "-42",
          memberAccepted: true,
          mode: "min",
          epoch: 0,
        });
      }),
    });
    const r = await client.counter("s").member("z").submit(-42n, { mode: "min" });
    expect(JSON.parse(sent)).toEqual({ value: "-42", mode: "min" });
    expect(r.memberValue).toBe("-42");
  });
});
