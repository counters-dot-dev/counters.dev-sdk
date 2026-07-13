package dev.counters.sdk;

import java.util.List;

/** One member's point list in a grouped member series response. */
public record MemberSeriesEntry(String member, List<SeriesPoint> points) {}
