package dev.counters.sdk;

import java.time.Instant;

/** One ranked leaderboard entry. {@code value} is an arbitrary-precision integer string. */
public record LeaderboardEntry(
        long rank,
        String member,
        String value,
        String metadata,
        Instant updatedAt) {}
