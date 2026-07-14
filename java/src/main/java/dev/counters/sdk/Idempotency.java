package dev.counters.sdk;

import java.util.UUID;

/**
 * Idempotency-key generation. Retrying the exact operation and payload with the same key is
 * de-duplicated within the service's deduplication window.
 */
public final class Idempotency {

    private Idempotency() {}

    /** Generate a fresh idempotency key (random v4 UUID). */
    public static String newKey() {
        try {
            return UUID.randomUUID().toString();
        } catch (RuntimeException failure) {
            throw new CountersTransportException("failed to generate an idempotency key", failure);
        }
    }

    /** Use a validated caller key, or generate a fresh key when none was supplied. */
    static String keyOrNew(String key) {
        if (key == null) return newKey();
        Validation.assertIdempotencyKey(key);
        return key;
    }
}
