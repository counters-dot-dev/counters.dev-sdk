package dev.counters.sdk;

/** One ranked leaderboard entry. {@code value} is an arbitrary-precision integer string. */
public record LeaderboardEntry(
        long rank,
        String member,
        String value,
        String metadata,
        String updatedAt) {}
