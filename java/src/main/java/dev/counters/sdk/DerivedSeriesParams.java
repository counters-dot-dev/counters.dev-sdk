package dev.counters.sdk;

import java.time.OffsetDateTime;

/** Read parameters for a derived series. */
public record DerivedSeriesParams(OffsetDateTime from, OffsetDateTime to, String bucket, String tz) {
    public DerivedSeriesParams {
        if (from == null || to == null) {
            throw new CountersValidationException("derived series: from and to are required");
        }
        Validation.assertBucket(bucket);
    }

    public DerivedSeriesParams(OffsetDateTime from, OffsetDateTime to, String bucket) {
        this(from, to, bucket, null);
    }
}
