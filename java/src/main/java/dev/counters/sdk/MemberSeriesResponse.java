package dev.counters.sdk;

import java.util.List;

/** One member's per-bucket delta series. */
public record MemberSeriesResponse(
        String counterKey,
        String member,
        String bucket,
        String mode,
        String timeZone,
        SeriesResponse.Range range,
        List<SeriesPoint> points) {}
