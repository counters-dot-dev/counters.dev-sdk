import { describe, expect, it, vi } from "vitest";
import { CountersClient } from "../src/client.js";
import { CountersValidationError } from "../src/errors.js";
import { jsonResponse, mockFetch } from "./helpers.js";

describe("counter declarations", () => {
  it("serializes an atomic full-set declaration and parses native dates", async () => {
    let request!: RequestInit;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((_url, init) => {
        request = init;
        return jsonResponse(200, {
          results: [{
            key: "requests",
            status: "created",
            epoch: 0,
            memberMode: "sum",
            memberSeriesEnabled: true,
            memberSeriesEnabledAt: "2026-08-10T00:00:00Z",
            memberSeriesEnabledBy: "api_key:key-id",
            memberCount: 0,
          }],
          undeclaredCounterWrites: "reject",
        });
      }),
    });

    const result = await client.declare({
      counters: [{ key: "requests", memberMode: "sum", memberSeriesEnabled: true }],
      undeclaredCounterWrites: "reject",
    });

    expect(request.method).toBe("POST");
    expect(JSON.parse(String(request.body))).toEqual({
      counters: [{ key: "requests", memberMode: "sum", memberSeriesEnabled: true }],
      undeclaredCounterWrites: "reject",
    });
    expect(result.results[0]?.memberSeriesEnabledAt).toEqual(
      new Date("2026-08-10T00:00:00Z"),
    );
    expect(result.undeclaredCounterWrites).toBe("reject");
    await client.close();
  });

  it("rejects empty, oversized, duplicate, and malformed declarations before I/O", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>();
    const client = new CountersClient({ apiKey: "k", fetch });

    expect(() => client.declare({ counters: [], undeclaredCounterWrites: "reject" })).toThrow(
      CountersValidationError,
    );
    expect(() => client.declare({
      counters: Array.from({ length: 1001 }, (_, i) => ({ key: `key-${i}` })),
      undeclaredCounterWrites: "allow",
    })).toThrow(CountersValidationError);
    expect(() => client.declare({
      counters: [{ key: "same" }, { key: "same" }],
      undeclaredCounterWrites: "reject",
    })).toThrow(/duplicate key/);
    expect(() => client.declare({
      counters: [{ key: "bad key" }],
      undeclaredCounterWrites: "reject",
    })).toThrow(CountersValidationError);
    expect(() => client.declare({
      counters: [{ key: "ok", memberMode: "median" as never }],
      undeclaredCounterWrites: "reject",
    })).toThrow(/memberMode/);
    expect(fetch).not.toHaveBeenCalled();
    await client.close();
  });
});

describe("counter configuration", () => {
  it("reads detail fields with native dates", async () => {
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((url) => {
        expect(url.pathname).toBe("/v1/counters/requests");
        return jsonResponse(200, {
          key: "requests",
          value: "12",
          epoch: 4,
          memberMode: "sum",
          memberSeriesEnabled: true,
          memberSeriesEnabledAt: "2026-08-10T01:00:00Z",
          memberSeriesEnabledBy: "api_key:key-id",
          memberCount: 3,
        });
      }),
    });

    const counter = await client.counter("requests").get();
    expect(counter.memberMode).toBe("sum");
    expect(counter.memberSeriesEnabled).toBe(true);
    expect(counter.memberSeriesEnabledAt).toEqual(new Date("2026-08-10T01:00:00Z"));
    expect(counter.memberSeriesEnabledBy).toBe("api_key:key-id");
    expect(counter.memberCount).toBe(3);
    await client.close();
  });

  it("sets member series with an optional epoch and parses the configuration", async () => {
    let request!: RequestInit;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((url, init) => {
        expect(url.pathname).toBe("/v1/counters/requests/member-series");
        request = init;
        return jsonResponse(200, {
          key: "requests",
          enabled: true,
          memberCount: 3,
          maxMembersWithSeries: 100,
          mode: "sum",
          enabledAt: "2026-08-10T01:00:00Z",
          enabledBy: "api_key:key-id",
        });
      }),
    });

    const config = await client.counter("requests").setMemberSeries(true, { expectedEpoch: 4 });
    expect(request.method).toBe("PUT");
    expect(JSON.parse(String(request.body))).toEqual({ enabled: true, expectedEpoch: 4 });
    expect(config.enabledAt).toEqual(new Date("2026-08-10T01:00:00Z"));
    expect(config.enabledBy).toBe("api_key:key-id");
    await client.close();
  });

  it("rejects malformed member-series arguments before I/O", async () => {
    const fetch = vi.fn<typeof globalThis.fetch>();
    const client = new CountersClient({ apiKey: "k", fetch });
    expect(() => client.counter("requests").setMemberSeries("yes" as never)).toThrow(
      CountersValidationError,
    );
    expect(() => client.counter("requests").setMemberSeries(true, { expectedEpoch: -1 })).toThrow(
      CountersValidationError,
    );
    expect(fetch).not.toHaveBeenCalled();
    await client.close();
  });
});
