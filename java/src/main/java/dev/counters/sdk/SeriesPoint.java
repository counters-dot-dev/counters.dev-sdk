package dev.counters.sdk;

import java.time.Instant;

/** One time-series bucket. {@code value} is an arbitrary-precision integer string. */
public record SeriesPoint(Instant timestamp, String value) {}
