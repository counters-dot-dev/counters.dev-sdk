package dev.counters.sdk;

import java.util.List;

/** Members ranked by summed activity over a trailing window. */
public record WindowLeaderboard(
        String key,
        String mode,
        String window,
        String order,
        String total,
        long memberCount,
        long limit,
        long offset,
        String effectiveStart,
        String effectiveEnd,
        List<WindowEntry> entries) {}
