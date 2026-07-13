/** Generate a fresh idempotency key. Retrying an operation with the same key is safe (server de-dups). */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}
