package dev.counters.sdk;

import java.time.Instant;

/** One derived series bucket. {@code value} is a decimal string, or null for a bucket-level hole. */
public record DerivedSeriesPoint(Instant timestamp, String value) {}
