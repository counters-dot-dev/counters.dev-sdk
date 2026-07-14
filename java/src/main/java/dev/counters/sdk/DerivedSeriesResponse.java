package dev.counters.sdk;

import java.util.List;

/** A derived counter evaluated per bucket over [from, to). Decimal values stay strings. */
public record DerivedSeriesResponse(
        String key,
        String bucket,
        String timeZone,
        long scale,
        SeriesResponse.Range range,
        List<DerivedSeriesPoint> points) {}
