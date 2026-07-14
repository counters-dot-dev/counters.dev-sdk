package dev.counters.sdk;

import java.time.Instant;
import java.util.List;

/**
 * A counter's time series (delta per bucket). Empty buckets are omitted unless gapfill was requested;
 * treat a missing bucket as zero.
 */
public record SeriesResponse(
        String counterKey,
        String bucket,
        String mode,
        String timeZone,
        Range range,
        List<SeriesPoint> points) {

    /** The [from, to) range the series covers. */
    public record Range(Instant from, Instant to) {}
}
