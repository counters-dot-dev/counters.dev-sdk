package dev.counters.sdk;

import java.time.Instant;

/** One result from a bulk declaration, in request order. */
public record CounterDeclarationResult(
        String key,
        String status,
        long epoch,
        String memberMode,
        boolean memberSeriesEnabled,
        Instant memberSeriesEnabledAt,
        String memberSeriesEnabledBy,
        long memberCount) {}
