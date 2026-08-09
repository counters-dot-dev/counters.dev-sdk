package dev.counters.sdk;

import java.time.Instant;

/** One result from a bulk declaration, in request order. */
public record CounterDeclarationResult(
        String key,
        String status,
        Long epoch,
        String memberMode,
        Boolean memberSeriesEnabled,
        Instant memberSeriesEnabledAt,
        String memberSeriesEnabledBy,
        Long memberCount,
        Problem error) {}
