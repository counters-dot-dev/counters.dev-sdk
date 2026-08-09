package dev.counters.sdk;

import java.time.Instant;

/** Current per-member time-series configuration for a counter. */
public record MemberSeriesConfig(
        String key,
        boolean enabled,
        long memberCount,
        long maxMembersWithSeries,
        String mode,
        Instant enabledAt,
        String enabledBy) {}
