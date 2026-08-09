package dev.counters.sdk;

import java.util.List;

/**
 * Per-member multi-series returned by {@code series?groupBy=member}: dense (gapfilled) on a sum
 * board, sparse per member on a score board. {@code mode} tells you how to read each point —
 * {@code "delta"} (per-bucket change) on a sum board, or the board mode ({@code "min"}/{@code "max"}/
 * {@code "latest"}) on a score board, where a missing bucket means "no submission", not zero.
 */
public record MemberGroupSeriesResponse(
        String counterKey,
        String bucket,
        String mode,
        String timeZone,
        SeriesResponse.Range range,
        long memberCount,
        long selectedCount,
        boolean truncated,
        List<MemberSeriesEntry> series) {}
