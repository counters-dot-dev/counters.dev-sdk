import type { Problem } from "./types.js";

/** Base error for all SDK failures. */
export class CountersError extends Error {
  readonly status?: number;
  readonly problem?: Problem;
  constructor(message: string, status?: number, problem?: Problem) {
    super(message);
    this.name = "CountersError";
    this.status = status;
    this.problem = problem;
  }
}

/** Thrown for client-side validation failures (bad counter key, bad amount) before any request is made. */
export class CountersValidationError extends CountersError {
  constructor(message: string) {
    super(message);
    this.name = "CountersValidationError";
  }
}

/**
 * Thrown for a terminal API failure: the server returned an HTTP error response (or a 2xx whose body
 * could not be parsed). `status` is always the HTTP status of that response (≥ 400 for errors);
 * `problem` is the parsed RFC 9457 `application/problem+json` document when the server sent one.
 * A `catch (e) { if (e instanceof CountersApiError) … }` catches API failures specifically; the base
 * `CountersError` still matches (so existing `catch CountersError` keeps working).
 */
export class CountersApiError extends CountersError {
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
  /** The last underlying transport failure (network error or timeout), when one was captured. */
  readonly cause?: unknown;
  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = "CountersTransportError";
    this.cause = cause;
  }
}
