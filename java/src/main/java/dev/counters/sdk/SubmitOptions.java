package dev.counters.sdk;

import java.time.Instant;

/** Options for a member score submit. */
public record SubmitOptions(String mode, String metadata, Instant occurredAt, String idempotencyKey) {
    public SubmitOptions {
        Validation.assertMode(mode);
        if (metadata != null) Validation.assertMetadata(metadata);
        if (idempotencyKey != null) Validation.assertIdempotencyKey(idempotencyKey);
    }

    public SubmitOptions(String mode, String metadata, Instant occurredAt) {
        this(mode, metadata, occurredAt, null);
    }

    public SubmitOptions(String mode) {
        this(mode, null, null, null);
    }

    public SubmitOptions(String mode, String metadata) {
        this(mode, metadata, null, null);
    }
}
