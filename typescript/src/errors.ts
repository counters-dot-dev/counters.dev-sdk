import type { Problem } from "./types.js";

/** Catchable root for the SDK's three error kinds. */
export abstract class CountersError extends Error {
  abstract readonly kind: "api" | "transport" | "validation";
  readonly status?: number;
  readonly problem?: Problem;
  protected constructor(message: string, status?: number, problem?: Problem) {
    super(message);
    this.name = "CountersError";
    this.status = status;
    this.problem = problem;
  }
}

/** Thrown for client-side validation failures (bad counter key, bad amount) before any request is made. */
export class CountersValidationError extends CountersError {
  readonly kind = "validation" as const;
  readonly cause?: unknown;
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "CountersValidationError";
    this.cause = cause;
  }
}

/**
 * Thrown for a terminal API failure: the server returned an HTTP error response (or a 2xx whose body
 * could not be parsed). `status` is always the real HTTP status of that response (normally ≥ 400,
 * but a malformed success body retains its 2xx status);
 * `problem` is the parsed RFC 9457 `application/problem+json` document when the server sent one.
 * A `catch (e) { if (e instanceof CountersApiError) … }` catches API failures specifically; the base
 * `CountersError` still matches (so existing `catch CountersError` keeps working).
 */
export class CountersApiError extends CountersError {
  readonly kind = "api" as const;
  declare readonly status: number;
  constructor(message: string, status: number, problem?: Problem) {
    super(message, status, problem);
    this.name = "CountersApiError";
  }
}

/**
 * Thrown when no HTTP response was obtained: a per-attempt timeout or network error that persisted
 * until retries were exhausted. Never carries a status (there was no response). This replaces the
 * former practice of surfacing exhausted-retry failures as an API error with a synthetic status.
 */
export class CountersTransportError extends CountersError {
  readonly kind = "transport" as const;
  /** The last underlying transport failure (network error or timeout), when one was captured. */
  readonly cause?: unknown;
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "CountersTransportError";
    this.cause = cause;
  }
}
