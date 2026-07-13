package dev.counters.sdk;

import java.util.List;

/** Dense per-member multi-series returned by {@code series?groupBy=member}. */
public record MemberGroupSeriesResponse(
        String counterKey,
        String bucket,
        String tz,
        SeriesResponse.Range range,
        List<MemberSeriesEntry> series) {}
