package dev.counters.sdk;

import java.util.UUID;

/** Idempotency-key generation. Retrying an operation with the same key is safe (server de-dups). */
public final class Idempotency {

    private Idempotency() {}

    /** Generate a fresh idempotency key (random v4 UUID). */
    public static String newKey() {
        return UUID.randomUUID().toString();
    }
}
