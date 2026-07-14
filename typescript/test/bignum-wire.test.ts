import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { CountersClient } from "../src/client.js";
import { jsonResponse, mockFetch } from "./helpers.js";

// The arbitrary-precision guarantee proven END TO END through the SDK's own request
// serialization and response parsing — not merely BigInt arithmetic on the vectors. If value were ever
// emitted or parsed as a JSON number, these would catch the double rounding.

const HUGE = "100000000000000000000000000000000"; // 10^32, far beyond u64
const HUGE_PLUS_ONE = "100000000000000000000000000000001";
const U64_CROSS = "18446744073709551616"; // 2^64

describe("bignum over the wire (mock transport)", () => {
  it("addNow serializes a 10^32 amount exactly and returns the response value exactly", async () => {
    let sentBody!: string;
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((_url, init) => {
        sentBody = init.body as string;
        return jsonResponse(200, { key: "big", value: HUGE_PLUS_ONE, epoch: 0 });
      }),
    });
    const counter = await client.counter("big").addNow(HUGE);
    // The amount must appear in the raw JSON as the exact digit string — never a number.
    expect(sentBody).toContain(`"amount":"${HUGE}"`);
    expect(JSON.parse(sentBody).amount).toBe(HUGE);
    expect(counter.value).toBe(HUGE_PLUS_ONE);
    expect(typeof counter.value).toBe("string");
    await client.close();
  });

  it("value() and series() surface huge values as exact strings", async () => {
    const client = new CountersClient({
      apiKey: "k",
      baseUrl: "https://x/v1",
      fetch: mockFetch((url) =>
        url.pathname.endsWith("/series")
          ? jsonResponse(200, {
              counterKey: "big",
              bucket: "1h",
              mode: "delta",
              range: { from: "2026-01-01T00:00:00Z", to: "2026-01-02T00:00:00Z" },
              points: [{ t: "2026-01-01T00:00:00Z", v: HUGE }],
            })
          : jsonResponse(200, { key: "big", value: U64_CROSS, epoch: 0 }),
      ),
    });
    expect((await client.counter("big").value()).value).toBe(U64_CROSS);
    const series = await client.counter("big").series({
      from: "2026-01-01T00:00:00Z",
      to: "2026-01-02T00:00:00Z",
      bucket: "1h",
    });
    expect(series.points[0]?.value).toBe(HUGE);
    expect(series.points[0]?.timestamp).toBeInstanceOf(Date);
    expect(series.points[0]?.timestamp.toISOString()).toBe("2026-01-01T00:00:00.000Z");
    await client.close();
  });
});

describe("bignum over a real socket (no injected fetch — the real global-fetch path)", () => {
  let server: Server;
  let baseUrl: string;
  const seen: { method?: string; url?: string; auth?: string; idem?: string; body?: string } = {};

  beforeAll(async () => {
    server = createServer((req: IncomingMessage, res: ServerResponse) => {
      let body = "";
      req.on("data", (c) => (body += c));
      req.on("end", () => {
        seen.method = req.method;
        seen.url = req.url;
        seen.auth = req.headers.authorization;
        seen.idem = req.headers["idempotency-key"] as string | undefined;
        seen.body = body;
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ key: "big", value: HUGE_PLUS_ONE, epoch: 0 }));
      });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const addr = server.address() as AddressInfo;
    baseUrl = `http://127.0.0.1:${addr.port}/v1`;
  });

  afterAll(async () => {
    await new Promise<void>((resolve, reject) => server.close((e) => (e ? reject(e) : resolve())));
  });

  it("round-trips 10^32 through real HTTP: exact request body, exact parsed value", async () => {
    const client = new CountersClient({ apiKey: "sk_real", baseUrl });
    const counter = await client.counter("big").addNow(HUGE);
    expect(seen.method).toBe("POST");
    expect(seen.url).toBe("/v1/counters/big/add");
    expect(seen.auth).toBe("Bearer sk_real");
    expect(seen.idem).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(seen.body!).amount).toBe(HUGE);
    expect(seen.body).toContain(`"amount":"${HUGE}"`);
    expect(counter.value).toBe(HUGE_PLUS_ONE);
    await client.close();
  });
});
