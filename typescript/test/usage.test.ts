import { describe, expect, it } from "vitest";
import { CountersClient } from "../src/client.js";
import { jsonResponse, mockFetch } from "./helpers.js";

describe("client.usage()", () => {
  it("GETs /v1/usage and parses the quota state (quotas may be null on unlimited plans)", async () => {
    let url!: URL;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((u) => {
        url = u;
        return jsonResponse(200, {
          month: "2026-07",
          ops: { used: 42, quota: null, resetsAt: "2026-08-01T00:00:00Z" },
          counters: { used: 3, max: 1000 },
          limits: { rateLimitRps: 50, maxCounters: 1000, monthlyOpsQuota: null },
        });
      }),
    });
    const usage = await client.usage();
    expect(url.pathname).toBe("/v1/usage");
    expect(usage.month).toBe("2026-07");
    expect(usage.operations.used).toBe(42);
    expect(usage.operations.quota).toBeNull();
    expect(usage.operations.resetsAt).toBeInstanceOf(Date);
    expect(usage.operations.resetsAt.toISOString()).toBe("2026-08-01T00:00:00.000Z");
    expect(usage.counters).toEqual({ used: 3, max: 1000 });
    expect(usage.limits.rateLimitRequestsPerSecond).toBe(50);
    expect(usage.limits.monthlyOperationsQuota).toBeNull();
    expect(usage).not.toHaveProperty("ops");
    expect(usage.limits).not.toHaveProperty("rateLimitRps");
    expect(usage.limits).not.toHaveProperty("monthlyOpsQuota");
  });

  it("carries a numeric quota when the plan is capped", async () => {
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch(() =>
        jsonResponse(200, {
          month: "2026-07",
          ops: { used: 900, quota: 1000, resetsAt: "2026-08-01T00:00:00Z" },
          counters: { used: 3, max: 25 },
          limits: { rateLimitRps: 10, maxCounters: 25, monthlyOpsQuota: 1000 },
        }),
      ),
    });
    const usage = await client.usage();
    expect(usage.operations.quota).toBe(1000);
    expect(usage.limits.monthlyOperationsQuota).toBe(1000);
  });
});
