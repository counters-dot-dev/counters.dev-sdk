package dev.counters.sdk;

import java.time.Instant;

/** Read parameters for a derived series. */
public record DerivedSeriesParams(Instant from, Instant to, String bucket, String timeZone) {
    public DerivedSeriesParams {
        if (from == null || to == null) {
            throw new CountersValidationException("derived series: from and to are required");
        }
        Validation.assertBucket(bucket);
    }

    public DerivedSeriesParams(Instant from, Instant to, String bucket) {
        this(from, to, bucket, null);
    }
}
