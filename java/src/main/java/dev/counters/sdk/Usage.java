package dev.counters.sdk;

/** Current quota state for the organization. Quota fields are null on unlimited plans. */
public record Usage(String month, Ops ops, Counters counters, Limits limits) {
    /** Write ops recorded this UTC month. */
    public record Ops(long used, Long quota, String resetsAt) {}

    /** Live counter usage for the organization. */
    public record Counters(long used, long max) {}

    /** Plan limits. */
    public record Limits(long rateLimitRps, long maxCounters, Long monthlyOpsQuota) {}
}
