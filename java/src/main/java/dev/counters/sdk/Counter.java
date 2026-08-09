package dev.counters.sdk;

import java.time.Instant;

/**
 * A counter's metadata and current value (mirrors {@code openapi/openapi.yaml}).
 *
 * <p>{@code value} is a signed arbitrary-precision integer as a decimal string — never parse it into a
 * {@code long}/{@code double} unless you know it fits; use {@code new java.math.BigInteger(value)}.
 * {@code epoch} is incremented by {@code clear}; the value sums deltas in the current epoch.
 */
public record Counter(
        String key,
        String value,
        long epoch,
        String memberMode,
        Boolean memberSeriesEnabled,
        Instant memberSeriesEnabledAt,
        String memberSeriesEnabledBy,
        Long memberCount,
        Instant createdAt,
        Instant updatedAt) {}
