package dev.counters.sdk;

import java.time.OffsetDateTime;

/** Options for an immediate member delta write. */
public record MemberWriteOptions(String metadata, OffsetDateTime occurredAt) {
    public MemberWriteOptions {
        if (metadata != null) Validation.assertMetadata(metadata);
    }

    public MemberWriteOptions(String metadata) {
        this(metadata, null);
    }

    public MemberWriteOptions(OffsetDateTime occurredAt) {
        this(null, occurredAt);
    }
}
