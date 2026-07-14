import { describe, expect, it, vi } from "vitest";
import { CountersClient } from "../src/client.js";
import { CountersApiError, CountersError, CountersTransportError, CountersValidationError } from "../src/errors.js";
import { Http } from "../src/http.js";
import { newIdempotencyKey } from "../src/idempotency.js";
import {
  assertBucket,
  assertMetadata,
  assertWindow,
  isValidCounterKey,
  toAmount,
  toValue,
} from "../src/validation.js";
import { jsonResponse, loadVectors, mockFetch } from "./helpers.js";

// B9: shared error-taxonomy vectors (conformance/errors/cases.json) driven through the real transport.
interface ApiCase {
  name: string;
  response: { status: number; body: unknown };
  expect: { taxonomy: "api"; status: number; title?: string };
}
interface TransportCase {
  name: string;
  transport: "network-error";
  expect: { taxonomy: "transport" };
}
interface ValidationCase {
  name: string;
  validate: { key?: string; amount?: string };
  expect: { taxonomy: "validation" };
}
interface BatchCase {
  name: string;
  response: { status: number; body: unknown };
  expect: { taxonomy: "api" | "validation"; status?: number; title?: string };
}
const vectors = loadVectors<{
  api: ApiCase[];
  transport: TransportCase[];
  validation: ValidationCase[];
  batch: BatchCase[];
}>("errors/cases.json");

const cfg = (fetchFn: typeof fetch) => ({ baseUrl: "https://x/v1", apiKey: "k", fetch: fetchFn, maxRetries: 0 });

describe("error taxonomy (conformance/errors)", () => {
  it.each(vectors.api)("api: $name", async (c) => {
    const f = mockFetch(
      () =>
        new Response(c.response.body == null ? "" : JSON.stringify(c.response.body), {
          status: c.response.status,
          headers: { "content-type": "application/json" },
        }),
    );
    const err = await new Http(cfg(f)).request("GET", "/x").then(() => null).catch((e) => e);
    expect(err).toBeInstanceOf(CountersApiError);
    expect(err).toBeInstanceOf(CountersError); // single catchable root
    expect((err as CountersApiError).status).toBe(c.expect.status);
    if (c.expect.title !== undefined) expect((err as Error).message).toContain(c.expect.title);
  });

  it.each(vectors.transport)("transport: $name", async (c) => {
    const f = mockFetch(() => {
      throw new TypeError("connection refused");
    });
    const err = await new Http(cfg(f)).request("GET", "/x").then(() => null).catch((e) => e);
    expect(err).toBeInstanceOf(CountersTransportError);
    expect(err).toBeInstanceOf(CountersError);
    expect(err).not.toBeInstanceOf(CountersApiError);
    expect((err as CountersError).status).toBeUndefined();
  });

  it.each(vectors.validation)("validation: $name", (c) => {
    if (c.validate.key !== undefined) {
      expect(isValidCounterKey(c.validate.key)).toBe(false);
    } else {
      expect(() => toAmount(c.validate.amount!)).toThrow(CountersValidationError);
    }
  });
});

// POST /batch HTTP-200 with a per-op "error" result.
// Driven as one buffered write + an explicit flush() through the real client + mock transport
// (retries disabled; the batch response is 200 so retries are moot).
describe("batch per-op errors (conformance/errors)", () => {
  it.each(vectors.batch)("batch: $name", async (c) => {
    const f = mockFetch(() => jsonResponse(c.response.status, c.response.body));
    const client = new CountersClient({ baseUrl: "https://x/v1", apiKey: "k", fetch: f, maxRetries: 0, batch: { intervalMs: 0 } });
    client.counter("signups").add(1);
    client.counter("capped").add(1);
    const err = await client.flush().then(() => null).catch((e) => e);
    if (c.expect.taxonomy === "api") {
      expect(err).toBeInstanceOf(CountersApiError);
      expect(err).toBeInstanceOf(CountersError); // single catchable root
      expect((err as CountersApiError).status).toBe(c.expect.status);
      if (c.expect.title !== undefined) expect((err as Error).message).toContain(c.expect.title);
    } else {
      expect(err).toBeInstanceOf(CountersValidationError);
      expect(err).not.toBeInstanceOf(CountersApiError);
      // Never an api error with a 0/undefined status here — undefined is required.
      expect((err as { status?: number }).status).toBeUndefined();
    }
  });
});

function expectExactlyOneTaxonomyKind(error: unknown): void {
  expect(error).toBeInstanceOf(CountersError);
  const kinds = [
    error instanceof CountersApiError,
    error instanceof CountersTransportError,
    error instanceof CountersValidationError,
  ].filter(Boolean);
  expect(kinds).toHaveLength(1);
  const sdkError = error as CountersError;
  if (error instanceof CountersApiError) expect(sdkError.kind).toBe("api");
  if (error instanceof CountersTransportError) expect(sdkError.kind).toBe("transport");
  if (error instanceof CountersValidationError) expect(sdkError.kind).toBe("validation");
}

describe("every SDK error producer stays inside the three-kind taxonomy", () => {
  it("covers construction, synchronous misuse, HTTP, response decoding, transport, and retries", async () => {
    const errors: unknown[] = [];
    for (const produce of [
      () => new CountersClient({ apiKey: "" }),
      () => new CountersClient({ apiKey: Symbol("key") as never }),
      () => new CountersClient({ apiKey: "k", fetch: 1 as never }),
      () => new CountersClient({ apiKey: "k", batch: null as never }),
      () => new CountersClient({ apiKey: "k", batch: { enabled: "yes" as never } }),
      () => new CountersClient({ apiKey: "k", batch: { maxBatchSize: 0 } }),
      () => new CountersClient({ apiKey: "k", batch: { intervalMs: Symbol("interval") as never } }),
      () => new CountersClient({ apiKey: "k", batch: { onError: "log" as never } }),
      () => new CountersClient({ apiKey: "k", baseUrl: "not a URL" }),
      () => new CountersClient({ apiKey: "k", baseUrl: 1n as never }),
      () => new CountersClient({ apiKey: "k" }).counter("has space"),
      () => new CountersClient({ apiKey: "k" }).counter(Symbol("counter") as never),
      () => new CountersClient({ apiKey: "k" }).counter("c").addNow(1, { idempotencyKey: "" }),
      () => new CountersClient({ apiKey: "k" }).counter("c").addNow(1, { idempotencyKey: null as never }),
      () => new CountersClient({ apiKey: "k" }).counter("c").addNow(1, { occurredAt: new Date(NaN) }),
      () => toAmount(Symbol("amount") as never),
      () => toValue(Symbol("value") as never),
      () => assertMetadata(Symbol("metadata") as never),
      () => assertBucket(1n as never),
      () => assertWindow(Symbol("window") as never),
    ]) {
      try {
        produce();
      } catch (error) {
        errors.push(error);
      }
    }

    const closed = new CountersClient({ apiKey: "k", batch: { intervalMs: 0 } });
    const handle = closed.counter("closed");
    await closed.close();
    try {
      handle.add(1);
    } catch (error) {
      errors.push(error);
    }

    const asyncProducers = [
      new Http(cfg(mockFetch(() => jsonResponse(400, { title: "bad" })))).request("GET", "/x"),
      new Http(cfg(mockFetch(() => new Response("{bad", { status: 200 })))).request("GET", "/x"),
      new CountersClient({
        apiKey: "k",
        baseUrl: "https://x/v1",
        fetch: mockFetch(() => jsonResponse(200, {})),
      }).list(),
      new Http(cfg(mockFetch(() => { throw new TypeError("offline"); }))).request("GET", "/x"),
      new Http(cfg((() => Promise.reject(Object.create(null))) as typeof fetch)).request("GET", "/x"),
      new Http(cfg((() => Promise.resolve(null)) as unknown as typeof fetch)).request("GET", "/x"),
      new Http(cfg((() => Promise.resolve({
        ok: false,
        status: 0,
        text: async () => "",
        json: async () => ({}),
        headers: new Headers(),
      })) as unknown as typeof fetch)).request("GET", "/x"),
    ];
    for (const producer of asyncProducers) {
      errors.push(await producer.then(() => null).catch((error) => error));
    }

    let attempts = 0;
    const mixed = new Http({
      ...cfg(mockFetch(() => {
        if (attempts++ === 0) return jsonResponse(503, { title: "busy" });
        throw new TypeError("offline after response");
      })),
      maxRetries: 1,
    });
    const mixedError = await mixed.request("GET", "/x").then(() => null).catch((error) => error);
    expect(mixedError).toBeInstanceOf(CountersApiError);
    expect((mixedError as CountersApiError).status).toBe(503);
    errors.push(mixedError);

    const randomUUID = vi.spyOn(globalThis.crypto, "randomUUID").mockImplementationOnce(() => {
      throw new Error("random source unavailable");
    });
    try {
      try {
        newIdempotencyKey();
      } catch (error) {
        errors.push(error);
      }
    } finally {
      randomUUID.mockRestore();
    }

    expect(errors).toHaveLength(30);
    for (const error of errors) expectExactlyOneTaxonomyKind(error);
  });
});
