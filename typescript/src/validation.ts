import { CountersValidationError } from "./errors.js";
import type { AmountInput, ValueInput } from "./types.js";

/** Allowed counter-key shape. Kept in lockstep with the server + OpenAPI (conformance/counter-keys.json). */
export const COUNTER_KEY_RE = /^[A-Za-z0-9._:-]{1,200}$/;

export function isValidCounterKey(key: string): boolean {
  return COUNTER_KEY_RE.test(key);
}

export function assertCounterKey(key: string): void {
  if (!isValidCounterKey(key)) {
    throw new CountersValidationError(`invalid counter key: ${JSON.stringify(key)}`);
  }
}

/**
 * Allowed member-key shape (openapi.yaml MemberKey; conformance/member-keys.json). Broader than a
 * counter key — it additionally allows `@` and `|` for composite member identities — and caps at 256.
 * A derived key, by contrast, reuses the counter-key shape (assertCounterKey) per openapi.yaml DerivedKey.
 */
export const MEMBER_KEY_RE = /^[A-Za-z0-9._:@|-]{1,256}$/;

export function isValidMemberKey(member: string): boolean {
  return MEMBER_KEY_RE.test(member);
}

export function assertMemberKey(member: string): void {
  if (!isValidMemberKey(member)) {
    throw new CountersValidationError(`invalid member key: ${JSON.stringify(member)}`);
  }
}

/**
 * Metadata byte cap (openapi.yaml Metadata; conformance/member-keys.json `metadata`). The limit is
 * **1024 UTF-8 bytes, not characters** — a multi-byte string can exceed it well under 1024 chars.
 * Counted with TextEncoder so the check matches the server's byte count exactly.
 */
export const METADATA_MAX_BYTES = 1024;

export function metadataByteLength(metadata: string): number {
  return new TextEncoder().encode(metadata).length;
}

export function isValidMetadata(metadata: string): boolean {
  return metadataByteLength(metadata) <= METADATA_MAX_BYTES;
}

export function assertMetadata(metadata: string): void {
  const bytes = metadataByteLength(metadata);
  if (bytes > METADATA_MAX_BYTES) {
    throw new CountersValidationError(
      `metadata exceeds ${METADATA_MAX_BYTES} UTF-8 bytes (got ${bytes})`,
    );
  }
}

/**
 * Allowed windowed-leaderboard sizes — the fixed spec enum (openapi.yaml leaderboard `window`,
 * conformance/leaderboard/cases.json). The `Window` union enforces this at compile time; this guard
 * fails fast at runtime (untyped/`as any` callers) before any request is issued.
 */
export const WINDOWS: readonly string[] = ["1h", "6h", "12h", "1d", "7d", "30d"];

export function isValidWindow(window: string): boolean {
  return WINDOWS.includes(window);
}

export function assertWindow(window: string): void {
  if (!isValidWindow(window)) {
    throw new CountersValidationError(
      `invalid window ${JSON.stringify(window)}; expected one of ${WINDOWS.join(", ")}`,
    );
  }
}

/**
 * Allowed series bucket sizes — the fixed spec enum (openapi.yaml SeriesParams.bucket,
 * conformance/buckets.json). Finer buckets may still be rejected server-side by plan; that is a
 * separate, non-local concern. The `Granularity` union enforces this at compile time; these guards
 * enforce it at runtime for untyped/`as any` callers and to fail fast before any request.
 */
export const BUCKETS: readonly string[] = ["1m", "5m", "1h", "1d", "1w", "1mo"];

export function isValidBucket(bucket: string): boolean {
  return BUCKETS.includes(bucket);
}

export function assertBucket(bucket: string): void {
  if (!isValidBucket(bucket)) {
    throw new CountersValidationError(
      `invalid bucket ${JSON.stringify(bucket)}; expected one of ${BUCKETS.join(", ")}`,
    );
  }
}

/**
 * Normalise an amount input to a non-negative bigint, validating arbitrary-precision integer rules.
 * Strings must match ^[0-9]+$ (conformance/amounts.json); numbers must be safe integers.
 */
export function toAmount(input: AmountInput): bigint {
  let v: bigint;
  if (typeof input === "bigint") {
    v = input;
  } else if (typeof input === "number") {
    if (!Number.isSafeInteger(input)) {
      throw new CountersValidationError(
        `amount number must be a safe integer (use a bigint or string for large values): ${input}`,
      );
    }
    v = BigInt(input);
  } else {
    if (!/^[0-9]+$/.test(input)) {
      throw new CountersValidationError(
        `amount string must be a non-negative integer: ${JSON.stringify(input)}`,
      );
    }
    v = BigInt(input);
  }
  if (v < 0n) {
    throw new CountersValidationError(`amount must be non-negative: ${v.toString()}`);
  }
  return v;
}

/**
 * Normalise a signed value input to a bigint (member `submit`). Mirrors {@link toAmount} but permits
 * a leading `-`: strings must match `^-?[0-9]+$` (conformance Value), numbers must be safe integers.
 * The value is arbitrary precision — always pass a bigint or string for magnitudes beyond 2^53.
 */
export function toValue(input: ValueInput): bigint {
  if (typeof input === "bigint") return input;
  if (typeof input === "number") {
    if (!Number.isSafeInteger(input)) {
      throw new CountersValidationError(
        `value number must be a safe integer (use a bigint or string for large values): ${input}`,
      );
    }
    return BigInt(input);
  }
  if (!/^-?[0-9]+$/.test(input)) {
    throw new CountersValidationError(
      `value string must be a signed integer: ${JSON.stringify(input)}`,
    );
  }
  return BigInt(input);
}
