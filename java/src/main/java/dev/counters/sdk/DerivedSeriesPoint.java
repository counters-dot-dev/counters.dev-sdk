package dev.counters.sdk;

/** One derived series bucket. {@code v} is a decimal string, or null for a bucket-level hole. */
public record DerivedSeriesPoint(String t, String v) {}
