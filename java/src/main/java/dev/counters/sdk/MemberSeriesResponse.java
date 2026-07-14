package dev.counters.sdk;

import java.util.List;

/**
 * One member's per-bucket series ({@code series?member=}). {@code mode} tells you how to read each
 * point: {@code "delta"} (the bucket's signed delta sum) on a sum board, or the board mode
 * ({@code "min"}/{@code "max"}/{@code "latest"} — bucket-best or bucket-latest) on a score board,
 * where points are sparse (a missing bucket means "no submission", not zero).
 */
public record MemberSeriesResponse(
        String counterKey,
        String member,
        String bucket,
        String mode,
        String timeZone,
        SeriesResponse.Range range,
        List<SeriesPoint> points) {}
