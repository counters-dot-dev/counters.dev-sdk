import {
  CountersApiError,
  CountersTransportError,
  CountersValidationError,
} from "./errors.js";
import { assertHeaderValue } from "./idempotency.js";
import { describeValue } from "./validation.js";
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
  private readonly baseUrl: string;

  constructor(private readonly cfg: HttpConfig) {
    this.baseUrl = validateBaseUrl(cfg.baseUrl);
    if (!cfg.apiKey) throw new CountersValidationError("CountersClient: apiKey is required");
    assertHeaderValue("apiKey", cfg.apiKey);
    if (cfg.maxRetries !== undefined && (!Number.isInteger(cfg.maxRetries) || cfg.maxRetries < 0)) {
      throw new CountersValidationError("maxRetries must be a non-negative integer");
    }
    if (cfg.backoffMs !== undefined && (!Number.isFinite(cfg.backoffMs) || cfg.backoffMs < 0)) {
      throw new CountersValidationError("backoffMs must be a non-negative finite number");
    }
    if (cfg.timeoutMs !== undefined && (!Number.isFinite(cfg.timeoutMs) || cfg.timeoutMs <= 0)) {
      throw new CountersValidationError("timeoutMs must be a positive finite number");
    }
    this.maxRetries = cfg.maxRetries ?? 3;
    this.backoffMs = cfg.backoffMs ?? 200;
    this.timeoutMs = cfg.timeoutMs ?? 30_000;
    this.sleepFn = cfg.sleep ?? sleep;
  }

  private get fetchFn(): typeof fetch {
    return this.cfg.fetch ?? globalThis.fetch;
  }

  async request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
    let url: URL;
    let requestBody: string | undefined;
    try {
      url = new URL(this.baseUrl + path);
      if (opts.query) {
        for (const [k, v] of Object.entries(opts.query)) {
          if (v !== undefined) url.searchParams.set(k, String(v));
        }
      }
      requestBody = opts.body === undefined ? undefined : JSON.stringify(opts.body);
    } catch (error) {
      throw new CountersValidationError(`cannot construct request: ${describeValue(error)}`, error);
    }
    const headers: Record<string, string> = { authorization: `Bearer ${this.cfg.apiKey}` };
    if (opts.body !== undefined) headers["content-type"] = "application/json";
    if (opts.idempotencyKey !== undefined) headers["idempotency-key"] = opts.idempotencyKey;

    let lastErr: unknown;
    let lastApiError: CountersApiError | undefined;
    let retryAfterMs: number | undefined;
    for (let attempt = 0; attempt <= this.maxRetries; attempt++) {
      // Honor a server Retry-After from the previous 429/5xx; otherwise exponential backoff.
      if (attempt > 0) {
        try {
          await this.sleepFn(retryAfterMs ?? this.backoffMs * 2 ** (attempt - 1));
        } catch (error) {
          if (lastApiError !== undefined) throw lastApiError;
          throw new CountersTransportError("request retry was interrupted before a response", error);
        }
      }
      retryAfterMs = undefined;

      let ok: boolean;
      let status: number;
      let readText: () => Promise<string>;
      let readJson: () => Promise<unknown>;
      let getHeader: (name: string) => string | null;
      const controller = new AbortController();
      const timer = setTimeout(
        () => controller.abort(new CountersTransportError(`request timed out after ${this.timeoutMs}ms`)),
        this.timeoutMs,
      );
      (timer as { unref?: () => void }).unref?.();
      try {
        const candidate: unknown = await this.fetchFn(url, {
          method,
          headers,
          body: requestBody,
          signal: controller.signal,
        });
        // `fetch` is injectable. Treat a replacement that resolves without a usable Fetch Response
        // as a transport failure (no valid HTTP response was obtained) instead of letting property
        // access leak a raw TypeError through the public client.
        if (candidate === null || (typeof candidate !== "object" && typeof candidate !== "function")) {
          throw new TypeError(`fetch returned ${describeValue(candidate)} instead of a Response`);
        }
        const response = candidate as Partial<Response>;
        ok = response.ok as boolean;
        status = response.status as number;
        if (typeof ok !== "boolean" || !Number.isInteger(status) || status < 100 || status > 599) {
          throw new TypeError("fetch returned an invalid Response status");
        }
        if (
          typeof response.text !== "function"
          || typeof response.json !== "function"
          || response.headers == null
          || typeof response.headers.get !== "function"
        ) {
          throw new TypeError("fetch returned an incomplete Response object");
        }
        readText = response.text.bind(candidate);
        readJson = response.json.bind(candidate);
        getHeader = response.headers.get.bind(response.headers);
      } catch (e) {
        lastErr = e; // network error or timeout — retry
        continue;
      } finally {
        clearTimeout(timer);
      }

      if (ok) {
        if (status === 204) return undefined as T;
        try {
          return JSON.parse(await readText()) as T;
        } catch (e) {
          // A non-JSON 2xx (captive portal / WAF `200 text/html`, truncated body) must not leak a
          // raw SyntaxError past the typed-error contract.
          throw new CountersApiError(
            `malformed response body (HTTP ${status}): ${describeValue(e)}`,
            status,
          );
        }
      }
      if (RETRYABLE_STATUS.has(status) && attempt < this.maxRetries) {
        try {
          retryAfterMs = parseRetryAfter(getHeader("retry-after"));
        } catch {
          // A malformed custom Headers implementation must not defeat an otherwise valid retry.
          retryAfterMs = undefined;
        }
        lastApiError = new CountersApiError(`HTTP ${status}`, status);
        continue;
      }
      let problem: Problem | undefined;
      try {
        problem = await readJson() as Problem | undefined;
      } catch {
        problem = undefined;
      }
      let title: string | undefined;
      try {
        if (typeof problem?.title === "string") title = problem.title;
      } catch {
        // A hostile custom Response body is still an HTTP response; retain the real status.
      }
      throw new CountersApiError(title ?? `HTTP ${status}`, status, problem);
    }
    // A transport error is reserved for calls that never obtained any HTTP response. If an earlier
    // retry did receive one, preserve that real API outcome rather than relabeling the call.
    if (lastApiError !== undefined) throw lastApiError;
    throw new CountersTransportError(
      `request failed after ${this.maxRetries + 1} attempts: ${describeValue(lastErr)}`,
      lastErr,
    );
  }
}

function validateBaseUrl(value: string): string {
  if (typeof value !== "string") {
    throw new CountersValidationError(`baseUrl must be a string, got ${describeValue(value)}`);
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch (error) {
    throw new CountersValidationError(
      `invalid baseUrl ${describeValue(value)}: ${describeValue(error)}`,
      error,
    );
  }
  if ((url.protocol !== "http:" && url.protocol !== "https:") || !url.host) {
    throw new CountersValidationError(
      `invalid baseUrl ${describeValue(value)}: expected an absolute HTTP(S) URL`,
    );
  }
  if (url.search || url.hash) {
    throw new CountersValidationError(
      `invalid baseUrl ${describeValue(value)}: query strings and fragments are not allowed`,
    );
  }
  return value.replace(/\/$/, "");
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
