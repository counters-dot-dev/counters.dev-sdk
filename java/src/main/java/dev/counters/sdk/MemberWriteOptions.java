package dev.counters.sdk;

import java.time.Instant;

/** Options for an immediate member delta write. */
public record MemberWriteOptions(String metadata, Instant occurredAt) {
    public MemberWriteOptions {
        if (metadata != null) Validation.assertMetadata(metadata);
    }

    public MemberWriteOptions(String metadata) {
        this(metadata, null);
    }

    public MemberWriteOptions(Instant occurredAt) {
        this(null, occurredAt);
    }
}
