package dev.counters.sdk;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Parameters for {@link CounterHandle#series(SeriesParams)}. {@code from}, {@code to}, and {@code bucket}
 * are required; {@code mode}, {@code tz} (IANA timezone for calendar bucket boundaries), and
 * {@code gapfill} are optional (pass {@code null} to omit).
 */
public record SeriesParams(
        OffsetDateTime from,
        OffsetDateTime to,
        String bucket,
        String mode,
        String tz,
        Boolean gapfill) {

    /** Allowed bucket sizes (finer buckets may require higher plans server-side). */
    public static final Set<String> BUCKETS = Validation.BUCKETS;

    public SeriesParams {
        if (from == null || to == null) {
            throw new CountersValidationException("series: from and to are required");
        }
        Validation.assertBucket(bucket);
        if (mode != null && !"delta".equals(mode)) {
            throw new CountersValidationException(
                    "series: mode must be \"delta\" when present: \"" + mode + "\"");
        }
    }

    /** Convenience constructor without the optional {@code tz} / {@code gapfill}. */
    public SeriesParams(OffsetDateTime from, OffsetDateTime to, String bucket) {
        this(from, to, bucket, null, null, null);
    }

    /** Back-compatible constructor without {@code mode}. */
    public SeriesParams(OffsetDateTime from, OffsetDateTime to, String bucket, String tz, Boolean gapfill) {
        this(from, to, bucket, null, tz, gapfill);
    }
}
