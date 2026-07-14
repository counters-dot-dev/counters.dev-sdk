package dev.counters.sdk;

import java.util.List;

/** A ranked member leaderboard. {@code total} is present only on sum boards. */
public record Leaderboard(
        String key,
        String mode,
        long epoch,
        String order,
        String total,
        long memberCount,
        long limit,
        long offset,
        List<LeaderboardEntry> entries) {}
