import { CountersApiError, CountersError, CountersTransportError } from "./errors.js";
import type { Problem } from "./types.js";

export interface HttpConfig {
  baseUrl: string;
  apiKey: string;
  fetch?: typeof fetch;
  maxRetries?: number;
  backoffMs?: number;
  /** Per-attempt request timeout in milliseconds (default 30s). A timed-out attempt is retried like a network error. */
  timeoutMs?: number;
  /** Internal seam: sleep between retries. Overridable in tests to record the backoff sequence. */
  sleep?: (ms: number) => Promise<void>;
}

type Query = Record<string, string | number | boolean | undefined>;

export interface RequestOptions {
  body?: unknown;
  idempotencyKey?: string;
  query?: Query;
}

const RETRYABLE_STATUS = new Set([429, 500, 502, 503, 504]);

/** Low-level transport: bearer auth, idempotency header, JSON, and retry-with-backoff on 429/5xx/network. */
export class Http {
  private readonly maxRetries: number;
  private readonly backoffMs: number;
  private readonly timeoutMs: number;
  private readonly sleepFn: (ms: number) => Promise<void>;

  constructor(private readonly cfg: HttpConfig) {
    this.maxRetries = cfg.maxRetries ?? 3;
    this.backoffMs = cfg.backoffMs ?? 200;
    this.timeoutMs = cfg.timeoutMs ?? 30_000;
    this.sleepFn = cfg.sleep ?? sleep;
  }

  private get fetchFn(): typeof fetch {
    return this.cfg.fetch ?? globalThis.fetch;
  }

  async request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
    const url = new URL(this.cfg.baseUrl.replace(/\/$/, "") + path);
    if (opts.query) {
      for (const [k, v] of Object.entries(opts.query)) {
        if (v !== undefined) url.searchParams.set(k, String(v));
      }
    }
    const headers: Record<string, string> = { authorization: `Bearer ${this.cfg.apiKey}` };
    if (opts.body !== undefined) headers["content-type"] = "application/json";
    if (opts.idempotencyKey) headers["idempotency-key"] = opts.idempotencyKey;

    let lastErr: unknown;
    let retryAfterMs: number | undefined;
    for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
      // Honor a server Retry-After from the previous 429/5xx; otherwise exponential backoff.
      if (attempt > 0) await this.sleepFn(retryAfterMs ?? this.backoffMs * 2 ** (attempt - 1));
      retryAfterMs = undefined;

      let res: Response;
      const controller = new AbortController();
      const timer = setTimeout(
        () => controller.abort(new CountersTransportError(`request timed out after ${this.timeoutMs}ms`)),
        this.timeoutMs,
      );
      (timer as { unref?: () => void }).unref?.();
      try {
        res = await this.fetchFn(url, {
          method,
          headers,
          body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
          signal: controller.signal,
        });
      } catch (e) {
        lastErr = e; // network error or timeout — retry
        continue;
      } finally {
        clearTimeout(timer);
      }

      if (res.ok) {
        if (res.status === 204) return undefined as T;
        try {
          return JSON.parse(await res.text()) as T;
        } catch (e) {
          // A non-JSON 2xx (captive portal / WAF `200 text/html`, truncated body) must not leak a
          // raw SyntaxError past the typed-error contract.
          throw new CountersApiError(`malformed response body (HTTP ${res.status}): ${String(e)}`, res.status);
        }
      }
      if (RETRYABLE_STATUS.has(res.status) && attempt < this.maxRetries) {
        retryAfterMs = parseRetryAfter(res.headers.get("retry-after"));
        lastErr = new CountersError(`HTTP ${res.status}`, res.status);
        continue;
      }
      const problem = (await res.json().catch(() => undefined)) as Problem | undefined;
      throw new CountersApiError(problem?.title ?? `HTTP ${res.status}`, res.status, problem);
    }
    throw new CountersTransportError(
      `request failed after ${this.maxRetries + 1} attempts: ${String(lastErr)}`,
      lastErr,
    );
  }
}

function sleep(ms: number): Promise<void> {
  return ms <= 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, ms));
}

/** Parse a `Retry-After` header as a non-negative integer number of seconds → milliseconds. Other
 *  forms (HTTP-date, garbage) return undefined so the caller falls back to exponential backoff. */
export function parseRetryAfter(value: string | null | undefined): number | undefined {
  if (value == null) return undefined;
  const secs = Number(value.trim());
  return Number.isInteger(secs) && secs >= 0 ? secs * 1000 : undefined;
}
