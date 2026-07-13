package dev.counters.sdk;

/** One time-series bucket: {@code t} is the bucket start (RFC 3339), {@code v} the delta as a decimal string. */
public record SeriesPoint(String t, String v) {}
