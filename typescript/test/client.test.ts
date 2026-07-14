import { describe, expect, it } from "vitest";
import { CountersClient, PublishableCountersClient } from "../src/client.js";
import { CountersApiError, CountersError, CountersValidationError } from "../src/errors.js";
import type { Operation } from "../src/types.js";
import { jsonResponse, mockFetch } from "./helpers.js";

describe("CountersClient", () => {
  it("rejects construction without an apiKey", () => {
    expect(() => new CountersClient({ apiKey: "" })).toThrow();
  });

  it("validates counter keys at counter()", () => {
    const c = new CountersClient({ apiKey: "k", fetch: mockFetch(() => jsonResponse(200, {})) });
    expect(() => c.counter("has space")).toThrow(CountersValidationError);
    expect(() => c.counter("ok.key")).not.toThrow();
  });

  it("publishable client sends its bearer and exposes only scoped read handles", async () => {
    const seenAuth: string[] = [];
    const client = new PublishableCountersClient({
      apiKey: "pk_browser",
      baseUrl: "https://x/v1",
      fetch: mockFetch((url, init) => {
        seenAuth.push((init.headers as Record<string, string>).authorization);
        if (url.pathname.endsWith("/series")) {
          return jsonResponse(200, {
            counterKey: "views",
            bucket: "1h",
            mode: "delta",
            range: { from: "2026-01-01T00:00:00Z", to: "2026-01-01T01:00:00Z" },
            points: [{ t: "2026-01-01T00:00:00Z", v: "7" }],
          });
        }
        if (url.pathname.includes("/members/")) {
          return jsonResponse(200, {
            key: "views",
            member: "alice",
            value: "7",
            rank: 1,
            percentile: "100.00",
            memberCount: 1,
            mode: "sum",
            epoch: 0,
            updatedAt: "2026-01-01T00:00:00Z",
          });
        }
        return jsonResponse(200, { key: "views", value: "7", epoch: 0 });
      }),
    });

    const counter = client.counter("views");
    expect(counter.key).toBe("views");
    expect((await counter.value()).value).toBe("7");
    const series = await counter.series({
      from: new Date("2026-01-01T00:00:00Z"),
      to: new Date("2026-01-01T01:00:00Z"),
      bucket: "1h",
    });
    expect(series.points[0]).toEqual({
      timestamp: new Date("2026-01-01T00:00:00Z"),
      value: "7",
    });
    const member = counter.member("alice");
    expect(member.counterKey).toBe("views");
    expect(member.member).toBe("alice");
    expect((await member.get()).updatedAt).toBeInstanceOf(Date);
    expect(seenAuth).toEqual(["Bearer pk_browser", "Bearer pk_browser", "Bearer pk_browser"]);

    expect((client as unknown as Record<string, unknown>).list).toBeUndefined();
    expect((client as unknown as Record<string, unknown>).flush).toBeUndefined();
    expect((counter as unknown as Record<string, unknown>).add).toBeUndefined();
    expect((member as unknown as Record<string, unknown>).remove).toBeUndefined();
    await client.close();
  });

  it("buffers adds and flushes one coalesced batch", async () => {
    const seen: { path: string; body: any }[] = [];
    const f = mockFetch((url, init) => {
      seen.push({ path: url.pathname, body: JSON.parse((init.body as string) ?? "null") });
      return jsonResponse(200, { results: [] });
    });
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1", batch: { intervalMs: 0 } });
    const reg = c.counter("registrations");
    reg.add(1);
    reg.add(2);
    reg.add(3);
    await c.flush();
    expect(seen).toHaveLength(1);
    expect(seen[0]!.path).toBe("/v1/batch");
    expect(seen[0]!.body.operations[0]).toMatchObject({
      counterKey: "registrations",
      op: "add",
      amount: "6",
    });
  });

  it("maps ergonomic batch operation fields to the compact wire shape", async () => {
    let body!: { operations: Record<string, unknown>[] };
    const c = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((_url, init) => {
        body = JSON.parse(init.body as string) as typeof body;
        return jsonResponse(200, { results: [] });
      }),
      batch: { intervalMs: 0 },
    });
    const operation: Operation = {
      counterKey: "registrations",
      operation: "add",
      amount: "1",
      occurredAt: new Date("2026-07-01T12:00:00Z"),
    };
    await (
      c as unknown as { submitBatch(operations: Operation[]): Promise<void> }
    ).submitBatch([operation]);
    expect(body.operations[0]).toEqual({
      counterKey: "registrations",
      op: "add",
      amount: "1",
      occurredAt: "2026-07-01T12:00:00.000Z",
    });
    expect(body.operations[0]).not.toHaveProperty("operation");
  });

  it("addNow forwards occurredAt for event-time bucketing", async () => {
    let body: any;
    const f = mockFetch((_url, init) => {
      body = JSON.parse((init.body as string) ?? "null");
      return jsonResponse(200, { key: "c", value: "1", epoch: 0 });
    });
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    await c.counter("c").addNow(1, { occurredAt: new Date("2026-07-01T12:00:00Z") });
    expect(body).toMatchObject({ amount: "1", occurredAt: "2026-07-01T12:00:00.000Z" });
    await c.counter("c").addNow(1);
    expect(body).not.toHaveProperty("occurredAt");
  });

  it("addNow hits the add endpoint immediately", async () => {
    const seen: string[] = [];
    const f = mockFetch((url) => {
      seen.push(url.pathname);
      return jsonResponse(200, {
        key: "c",
        value: "1",
        epoch: 0,
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:01Z",
      });
    });
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    const r = await c.counter("c").addNow(1);
    expect(seen[0]).toBe("/v1/counters/c/add");
    expect(r.value).toBe("1");
    expect(r.createdAt?.toISOString()).toBe("2026-01-01T00:00:00.000Z");
    expect(r.updatedAt?.toISOString()).toBe("2026-01-01T00:00:01.000Z");
  });

  it("subtractNow parses counter timestamps into Dates", async () => {
    const f = mockFetch(() =>
      jsonResponse(200, {
        key: "c",
        value: "-1",
        epoch: 0,
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:02Z",
      }),
    );
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    const r = await c.counter("c").subtractNow(1);
    expect(r.createdAt).toBeInstanceOf(Date);
    expect(r.updatedAt).toBeInstanceOf(Date);
    expect(r.updatedAt?.toISOString()).toBe("2026-01-01T00:00:02.000Z");
  });

  it("value() GETs the value endpoint", async () => {
    const f = mockFetch(() => jsonResponse(200, { key: "c", value: "-5", epoch: 2 }));
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    expect(await c.counter("c").value()).toMatchObject({ value: "-5", epoch: 2 });
  });

  it("series() passes through query params", async () => {
    let url!: URL;
    const f = mockFetch((u) => {
      url = u;
      return jsonResponse(200, {
        counterKey: "c",
        bucket: "1h",
        mode: "delta",
        tz: "Europe/London",
        range: { from: "2026-01-01T00:00:00Z", to: "2026-01-02T00:00:00Z" },
        points: [],
      });
    });
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    await c.counter("c").series({
      from: new Date("2026-01-01T00:00:00Z"),
      to: new Date("2026-01-02T00:00:00Z"),
      bucket: "1h",
      timeZone: "Europe/London",
    });
    expect(url.pathname).toBe("/v1/counters/c/series");
    expect(url.searchParams.get("bucket")).toBe("1h");
    expect(url.searchParams.get("from")).toBe("2026-01-01T00:00:00.000Z");
    expect(url.searchParams.get("tz")).toBe("Europe/London");
    const result = await c.counter("c").series({
      from: new Date("2026-01-01T00:00:00Z"),
      to: new Date("2026-01-02T00:00:00Z"),
      bucket: "1h",
    });
    expect(result.timeZone).toBe("Europe/London");
    expect(result.range.from).toBeInstanceOf(Date);
    expect(result.range.to.toISOString()).toBe("2026-01-02T00:00:00.000Z");
    expect(result).not.toHaveProperty("tz");
  });

  it("omits gapfill when false, sends it only when true", async () => {
    let url!: URL;
    const f = mockFetch((u) => {
      url = u;
      return jsonResponse(200, { counterKey: "c", bucket: "1d", mode: "delta", range: { from: "2026-01-01T00:00:00Z", to: "2026-01-02T00:00:00Z" }, points: [] });
    });
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    const base = {
      from: new Date("2026-01-01T00:00:00Z"),
      to: new Date("2026-01-02T00:00:00Z"),
      bucket: "1d",
    } as const;

    // omit-when-false: an explicit gapfill:false must not put gapfill=false on the wire.
    await c.counter("c").series({ ...base, gapfill: false });
    expect(url.searchParams.has("gapfill")).toBe(false);

    await c.counter("c").series({ ...base });
    expect(url.searchParams.has("gapfill")).toBe(false);

    await c.counter("c").series({ ...base, gapfill: true });
    expect(url.searchParams.get("gapfill")).toBe("true");
  });

  it("serializes native Date series bounds", async () => {
    let url!: URL;
    const f = mockFetch((u) => {
      url = u;
      return jsonResponse(200, { counterKey: "c", bucket: "1d", mode: "delta", range: { from: "2026-01-01T00:00:00Z", to: "2026-01-02T00:00:00Z" }, points: [] });
    });
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    await c.counter("c").series({ from: new Date("2026-01-01T00:00:00Z"), to: new Date("2026-01-02T00:00:00Z"), bucket: "1d" });
    expect(url.searchParams.get("from")).toBe("2026-01-01T00:00:00.000Z");
  });

  it("immediate mode (batch disabled) fires per-op without buffering", async () => {
    const seen: string[] = [];
    const f = mockFetch((url) => {
      seen.push(url.pathname);
      return jsonResponse(200, { results: [] });
    });
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1", batch: { enabled: false } });
    c.counter("c").add(1);
    await new Promise((r) => setTimeout(r, 0)); // let the fire-and-forget submit run
    expect(seen[0]).toBe("/v1/batch");
  });

  it("immediate mode routes write failures to batch.onError instead of swallowing them", async () => {
    const errors: unknown[] = [];
    const f = mockFetch(() => jsonResponse(403, { title: "quota exceeded", status: 403 }));
    const c = new CountersClient({
      apiKey: "k",
      fetch: f,
      baseUrl: "https://x/v1",
      maxRetries: 0,
      batch: { enabled: false, onError: (e) => errors.push(e) },
    });
    c.counter("c").add(1);
    await new Promise((r) => setTimeout(r, 0));
    expect(errors).toHaveLength(1);
    expect(errors[0]).toBeInstanceOf(CountersApiError);
    expect((errors[0] as CountersApiError).status).toBe(403);
  });

  it("immediate mode rejects writes after close, like the buffered path", async () => {
    const f = mockFetch(() => jsonResponse(200, { results: [] }));
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1", batch: { enabled: false } });
    await c.close();
    expect(() => c.counter("c").add(1)).toThrow(CountersError);
  });

  it("surfaces a per-operation batch error instead of silently dropping it", async () => {
    const f = mockFetch(() =>
      jsonResponse(200, {
        results: [
          { counterKey: "a", status: "applied", value: "1" },
          { counterKey: "b", status: "error", error: { title: "counter limit reached", status: 403 } },
        ],
      }),
    );
    const c = new CountersClient({ apiKey: "k", fetch: f, baseUrl: "https://x/v1" });
    c.counter("a").add(1);
    c.counter("b").add(1);
    // A per-op problem carrying a status maps to the api type, not the bare root.
    const err = await c.flush().then(() => null).catch((e) => e);
    expect(err).toBeInstanceOf(CountersApiError);
    expect((err as CountersApiError).status).toBe(403);
  });
});
