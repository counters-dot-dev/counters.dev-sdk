package dev.counters.sdk;

/** A member's rank, percentile, and standing value within a board. */
public record MemberSnapshot(
        String key,
        String member,
        String value,
        String metadata,
        long rank,
        String percentile,
        long memberCount,
        String mode,
        long epoch,
        String updatedAt) {}
