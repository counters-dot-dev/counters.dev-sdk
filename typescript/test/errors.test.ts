import { describe, expect, it } from "vitest";
import { CountersClient } from "../src/client.js";
import { CountersApiError, CountersError, CountersTransportError, CountersValidationError } from "../src/errors.js";
import { Http } from "../src/http.js";
import { isValidCounterKey, toAmount } from "../src/validation.js";
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
