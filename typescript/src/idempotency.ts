import { CountersTransportError, CountersValidationError } from "./errors.js";
import { describeValue } from "./validation.js";

export const IDEMPOTENCY_KEY_MAX_LENGTH = 255;

/** Generate a fresh idempotency key for one exact write payload. */
export function newIdempotencyKey(): string {
  try {
    return globalThis.crypto.randomUUID();
  } catch (error) {
    throw new CountersTransportError("failed to generate an idempotency key", error);
  }
}

/** Resolve a caller key or generate one, rejecting invalid explicit keys before any request. */
export function resolveIdempotencyKey(idempotencyKey: string | undefined): string {
  if (idempotencyKey === undefined) return newIdempotencyKey();
  if (typeof idempotencyKey !== "string") {
    throw new CountersValidationError(
      `idempotency key must be a string, got ${describeValue(idempotencyKey)}`,
    );
  }
  if (idempotencyKey.length === 0) {
    throw new CountersValidationError("idempotency key must not be empty");
  }
  if (idempotencyKey.length > IDEMPOTENCY_KEY_MAX_LENGTH) {
    throw new CountersValidationError(
      `idempotency key must be at most ${IDEMPOTENCY_KEY_MAX_LENGTH} characters`,
    );
  }
  assertHeaderValue("idempotency key", idempotencyKey);
  return idempotencyKey;
}

/** Reject values that the Fetch Headers implementation would reject later as a raw TypeError. */
export function assertHeaderValue(name: string, value: string): void {
  if (typeof value !== "string") {
    throw new CountersValidationError(`${name} must be a string, got ${describeValue(value)}`);
  }
  if (/[^\t\x20-\x7e\x80-\xff]/.test(value)) {
    throw new CountersValidationError(`${name} contains an invalid HTTP header character`);
  }
}
