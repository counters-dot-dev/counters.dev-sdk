package dev.counters.sdk;

import java.time.Instant;
import java.util.List;

/**
 * Members ranked by their activity over a trailing window: the window-sum on a {@code sum} board,
 * the window-best ({@code min}/{@code max}) or window-latest ({@code latest}) value on a score
 * board. {@code total} is the window group total — non-null only on {@code sum} boards (a sum of
 * best lap times is nonsense). {@code effectiveStart}/{@code effectiveEnd} are the bounds actually
 * covered (the start is floored to a rollup boundary).
 */
public record WindowLeaderboard(
        String key,
        String mode,
        String window,
        String order,
        String total,
        long memberCount,
        long limit,
        long offset,
        Instant effectiveStart,
        Instant effectiveEnd,
        List<WindowEntry> entries) {}
