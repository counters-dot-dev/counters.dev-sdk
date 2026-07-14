package dev.counters.sdk;

import java.time.Instant;

/** Current quota state for the organization. Quota fields are null on unlimited plans. */
public record Usage(String month, Operations operations, Counters counters, Limits limits) {
    /** Write ops recorded this UTC month. */
    public record Operations(long used, Long quota, Instant resetsAt) {}

    /** Live counter usage for the organization. */
    public record Counters(long used, long max) {}

    /** Plan limits. */
    public record Limits(
            long rateLimitRequestsPerSecond,
            long maxCounters,
            Long monthlyOperationsQuota) {}
}
