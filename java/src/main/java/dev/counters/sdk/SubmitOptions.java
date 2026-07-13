package dev.counters.sdk;

import java.time.OffsetDateTime;

/** Options for a member score submit. */
public record SubmitOptions(String mode, String metadata, OffsetDateTime occurredAt) {
    public SubmitOptions {
        Validation.assertMode(mode);
        if (metadata != null) Validation.assertMetadata(metadata);
    }

    public SubmitOptions(String mode) {
        this(mode, null, null);
    }

    public SubmitOptions(String mode, String metadata) {
        this(mode, metadata, null);
    }
}
