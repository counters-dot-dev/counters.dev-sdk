package dev.counters.sdk;

import java.util.List;

/** One page of counters. {@code nextCursor} is non-null when more results exist. */
public record CounterPage(List<Counter> data, String nextCursor) {}
