import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

// test/ -> typescript/ -> repo root
const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

export function loadVectors<T = any>(name: string): T {
  return JSON.parse(readFileSync(join(repoRoot, "conformance", name), "utf8"));
}

export function loadFormatVectors<T = any>(): T {
  return JSON.parse(readFileSync(join(repoRoot, "format", "vectors.json"), "utf8"));
}

/** Build a fetch stand-in from a handler that receives the URL and init. */
export function mockFetch(
  handler: (url: URL, init: RequestInit) => Response | Promise<Response>,
): typeof fetch {
  return ((input: unknown, init?: RequestInit) =>
    Promise.resolve(handler(new URL(String(input)), init ?? {}))) as unknown as typeof fetch;
}

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}
