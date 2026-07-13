import { describe, expect, it } from "vitest";
import { CountersClient } from "../src/client.js";
import { jsonResponse, mockFetch } from "./helpers.js";

// list / clear / delete were untested.

describe("list", () => {
  it("encodes cursor+limit and parses a page with nextCursor", async () => {
    let url!: URL;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((u) => {
        url = u;
        return jsonResponse(200, {
          data: [
            { key: "a", value: "1", epoch: 0 },
            { key: "b", value: "18446744073709551616", epoch: 2 },
          ],
          nextCursor: "b",
        });
      }),
    });
    const page = await client.list({ cursor: "prev", limit: 2 });
    expect(url.pathname).toBe("/v1/counters");
    expect(url.searchParams.get("cursor")).toBe("prev");
    expect(url.searchParams.get("limit")).toBe("2");
    expect(page.data.map((c) => c.key)).toEqual(["a", "b"]);
    expect(page.data[1]?.value).toBe("18446744073709551616"); // >u64 stays a string
    expect(page.nextCursor).toBe("b");
    await client.close();
  });
});

describe("clear", () => {
  it("POSTs the clear path with an idempotency key and parses the reset counter", async () => {
    let url!: URL;
    let init!: RequestInit;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((u, i) => {
        url = u;
        init = i;
        return jsonResponse(200, { key: "c", value: "0", epoch: 3 });
      }),
    });
    const counter = await client.counter("c").clear();
    expect(url.pathname).toBe("/v1/counters/c/clear");
    expect((init.headers as Record<string, string>)["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(counter).toEqual({ key: "c", value: "0", epoch: 3 });
    await client.close();
  });
});

describe("delete", () => {
  it("sends DELETE and resolves on 204", async () => {
    let url!: URL;
    let init!: RequestInit;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((u, i) => {
        url = u;
        init = i;
        return new Response(null, { status: 204 });
      }),
    });
    await expect(client.counter("gone").delete()).resolves.toBeUndefined();
    expect(init.method).toBe("DELETE");
    expect(url.pathname).toBe("/v1/counters/gone");
    await client.close();
  });

  it("percent-encodes reserved key characters in the path", async () => {
    let url!: URL;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((u) => {
        url = u;
        return jsonResponse(200, { key: "enc:a.b", value: "1", epoch: 0 });
      }),
    });
    await client.counter("enc:a.b").value();
    expect(url.pathname).toBe("/v1/counters/enc%3Aa.b/value");
    await client.close();
  });
});
