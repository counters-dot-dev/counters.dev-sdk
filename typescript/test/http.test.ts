import { describe, expect, it, vi } from "vitest";
import { CountersApiError, CountersError, CountersTransportError } from "../src/errors.js";
import { Http } from "../src/http.js";
import { jsonResponse, mockFetch } from "./helpers.js";

const cfg = (fetchFn: typeof fetch, extra: Partial<{ maxRetries: number }> = {}) => ({
  baseUrl: "https://x/v1",
  apiKey: "k",
  fetch: fetchFn,
  backoffMs: 0,
  ...extra,
});

describe("Http transport", () => {
  it("sends bearer auth, idempotency, and content-type headers", async () => {
    let init!: RequestInit;
    const f = mockFetch((_url, i) => {
      init = i;
      return jsonResponse(200, { ok: true });
    });
    await new Http(cfg(f)).request("POST", "/x", { body: { a: 1 }, idempotencyKey: "id-1" });
    const headers = init.headers as Record<string, string>;
    expect(headers.authorization).toBe("Bearer k");
    expect(headers["idempotency-key"]).toBe("id-1");
    expect(headers["content-type"]).toBe("application/json");
    expect(JSON.parse(init.body as string)).toEqual({ a: 1 });
  });

  it("parses JSON on 200", async () => {
    const f = mockFetch(() => jsonResponse(200, { value: "42" }));
    const r = await new Http(cfg(f)).request<{ value: string }>("GET", "/x");
    expect(r.value).toBe("42");
  });

  it("returns undefined on 204", async () => {
    const f = mockFetch(() => new Response(null, { status: 204 }));
    expect(await new Http(cfg(f)).request("DELETE", "/x")).toBeUndefined();
  });

  it("retries on 429 then succeeds", async () => {
    let n = 0;
    const calls = vi.fn();
    const f = mockFetch(() => {
      calls();
      return ++n < 3 ? jsonResponse(429, { title: "rate" }) : jsonResponse(200, { ok: true });
    });
    expect(await new Http(cfg(f)).request("GET", "/x")).toEqual({ ok: true });
    expect(calls).toHaveBeenCalledTimes(3);
  });

  it("retries on 503 then succeeds", async () => {
    let n = 0;
    const f = mockFetch(() => (++n < 2 ? jsonResponse(503, {}) : jsonResponse(200, { ok: true })));
    await expect(new Http(cfg(f)).request("GET", "/x")).resolves.toEqual({ ok: true });
  });

  it("does NOT retry on 400", async () => {
    const calls = vi.fn();
    const f = mockFetch(() => {
      calls();
      return jsonResponse(400, { title: "bad" });
    });
    const err = await new Http(cfg(f))
      .request("POST", "/x")
      .then(() => null)
      .catch((e) => e);
    // B1: an HTTP error response surfaces as CountersApiError (still a CountersError).
    expect(err).toBeInstanceOf(CountersApiError);
    expect(err).toBeInstanceOf(CountersError);
    expect(err.status).toBe(400);
    expect(calls).toHaveBeenCalledOnce();
  });

  it("retries network errors then throws CountersTransportError after maxRetries (B2, no status)", async () => {
    const calls = vi.fn();
    const f = mockFetch(() => {
      calls();
      throw new TypeError("network down");
    });
    const err = await new Http(cfg(f, { maxRetries: 2 }))
      .request("GET", "/x")
      .then(() => null)
      .catch((e) => e);
    // B2: no HTTP response ⇒ transport error, never an API error with a synthetic status 0.
    expect(err).toBeInstanceOf(CountersTransportError);
    expect(err).not.toBeInstanceOf(CountersApiError);
    expect(err.status).toBeUndefined();
    expect(calls).toHaveBeenCalledTimes(3); // initial + 2 retries
  });

  it("normalises hostile fetch failures and invalid resolved values as transport errors", async () => {
    const unprintable = Object.create(null) as unknown;
    const rejecting = (() => Promise.reject(unprintable)) as unknown as typeof fetch;
    const rejected = await new Http(cfg(rejecting, { maxRetries: 0 }))
      .request("GET", "/x")
      .then(() => null)
      .catch((error) => error);
    expect(rejected).toBeInstanceOf(CountersTransportError);
    expect(rejected).toBeInstanceOf(CountersError);

    const resolvingNull = (() => Promise.resolve(null)) as unknown as typeof fetch;
    const invalidResponse = await new Http(cfg(resolvingNull, { maxRetries: 0 }))
      .request("GET", "/x")
      .then(() => null)
      .catch((error) => error);
    expect(invalidResponse).toBeInstanceOf(CountersTransportError);
    expect(invalidResponse).toBeInstanceOf(CountersError);

    const resolvingInvalidStatus = (() => Promise.resolve({
      ok: false,
      status: 0,
      text: async () => "",
      json: async () => ({}),
      headers: new Headers(),
    })) as unknown as typeof fetch;
    const invalidStatus = await new Http(cfg(resolvingInvalidStatus, { maxRetries: 0 }))
      .request("GET", "/x")
      .then(() => null)
      .catch((error) => error);
    expect(invalidStatus).toBeInstanceOf(CountersTransportError);
    expect(invalidStatus).toBeInstanceOf(CountersError);
  });

  it("wraps a malformed or empty 2xx body in a CountersApiError carrying the real status", async () => {
    for (const body of ["", "{bad json", "<html>nope</html>"]) {
      const f = mockFetch(
        () => new Response(body, { status: 200, headers: { "content-type": "application/json" } }),
      );
      const err = await new Http(cfg(f))
        .request("GET", "/x")
        .then(() => null)
        .catch((e) => e);
      expect(err, `body ${JSON.stringify(body)} should reject`).toBeInstanceOf(CountersApiError);
      expect((err as CountersApiError).status).toBe(200);
    }
  });

  it("attaches problem details to a thrown CountersApiError", async () => {
    const f = mockFetch(() => jsonResponse(404, { title: "Counter not found", status: 404 }));
    const err = await new Http(cfg(f))
      .request("GET", "/x")
      .then(() => null)
      .catch((e) => e);
    expect(err).toBeInstanceOf(CountersApiError);
    expect(err).toMatchObject({ status: 404, problem: { title: "Counter not found" } });
  });

  it("aborts a hung request after timeoutMs and retries it like a network error", async () => {
    const calls = vi.fn();
    // A fetch that never resolves on its own — only the abort signal can end it.
    const hung = ((_input: unknown, init?: RequestInit) => {
      calls();
      return new Promise<Response>((_, reject) => {
        init?.signal?.addEventListener("abort", () => reject(init.signal!.reason ?? new Error("aborted")));
      });
    }) as unknown as typeof fetch;
    const http = new Http({ baseUrl: "https://x/v1", apiKey: "k", fetch: hung, backoffMs: 0, maxRetries: 1, timeoutMs: 10 });
    const err = await http.request("GET", "/x").then(() => null).catch((e) => e);
    // B2: an exhausted-retry timeout is a transport error (no response ever arrived).
    expect(err).toBeInstanceOf(CountersTransportError);
    expect(err.message).toMatch(/request failed after 2 attempts.*timed out after 10ms/s);
    expect(calls).toHaveBeenCalledTimes(2); // initial + 1 retry, each individually timed out
  });

  it("passes an abort signal to fetch on every request", async () => {
    let init!: RequestInit;
    const f = mockFetch((_url, i) => {
      init = i;
      return jsonResponse(200, {});
    });
    await new Http(cfg(f)).request("GET", "/x");
    expect(init.signal).toBeInstanceOf(AbortSignal);
    expect(init.signal?.aborted).toBe(false);
  });

  it("honors a Retry-After header over exponential backoff", async () => {
    const delays: number[] = [];
    let n = 0;
    const f = mockFetch(() =>
      ++n < 2
        ? new Response(null, { status: 503, headers: { "retry-after": "2" } })
        : jsonResponse(200, { ok: true }),
    );
    await new Http({ ...cfg(f), sleep: async (ms) => void delays.push(ms) }).request("GET", "/x");
    expect(delays).toEqual([2000]); // 2s from Retry-After, not the (zeroed) exponential base
  });

  it("grows the retry backoff exponentially when there is no Retry-After", async () => {
    const delays: number[] = [];
    const f = mockFetch(() => jsonResponse(503, {})); // always retryable, no Retry-After
    await new Http({
      baseUrl: "https://x/v1",
      apiKey: "k",
      fetch: f,
      backoffMs: 100,
      maxRetries: 3,
      sleep: async (ms) => void delays.push(ms),
    })
      .request("GET", "/x")
      .catch(() => undefined);
    expect(delays).toEqual([100, 200, 400]); // base, 2x, 4x — a linear/constant refactor would fail here
  });

  it("percent-encodes hostile query values", async () => {
    let url!: URL;
    const f = mockFetch((u) => {
      url = u;
      return jsonResponse(200, {});
    });
    await new Http(cfg(f)).request("GET", "/x", { query: { tz: "a&b=c#d e", cursor: "café/n?x" } });
    expect(url.searchParams.get("tz")).toBe("a&b=c#d e"); // round-trips through encode/decode
    expect(url.searchParams.get("cursor")).toBe("café/n?x");
    expect(url.search).toContain("tz=a%26b%3Dc%23d"); // reserved delimiters are escaped on the wire
  });

  it("encodes query params and omits undefined", async () => {
    let url!: URL;
    const f = mockFetch((u) => {
      url = u;
      return jsonResponse(200, {});
    });
    await new Http(cfg(f)).request("GET", "/x", { query: { a: "1", b: 2, c: true, d: undefined } });
    expect(url.searchParams.get("a")).toBe("1");
    expect(url.searchParams.get("b")).toBe("2");
    expect(url.searchParams.get("c")).toBe("true");
    expect(url.searchParams.has("d")).toBe(false);
  });
});
