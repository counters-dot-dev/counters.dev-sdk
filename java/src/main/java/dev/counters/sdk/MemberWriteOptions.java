package dev.counters.sdk;

import java.time.Instant;

/** Options for an immediate member delta write. */
public record MemberWriteOptions(String metadata, Instant occurredAt, String idempotencyKey) {
    public MemberWriteOptions {
        if (metadata != null) Validation.assertMetadata(metadata);
        if (idempotencyKey != null) Validation.assertIdempotencyKey(idempotencyKey);
    }

    public MemberWriteOptions(String metadata, Instant occurredAt) {
        this(metadata, occurredAt, null);
    }

    public MemberWriteOptions(String metadata) {
        this(metadata, null, null);
    }

    public MemberWriteOptions(Instant occurredAt) {
        this(null, occurredAt, null);
    }
}
