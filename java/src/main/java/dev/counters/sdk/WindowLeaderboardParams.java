package dev.counters.sdk;

/**
 * Read parameters for a windowed leaderboard. {@code epoch} is accepted for parameter symmetry with
 * {@link LeaderboardParams} but ignored by windowed reads (member rollups are epoch-agnostic).
 */
public record WindowLeaderboardParams(String window, Integer limit, Integer offset, String order, Long epoch) {
    public WindowLeaderboardParams {
        Validation.assertWindow(window);
    }

    public WindowLeaderboardParams(String window) {
        this(window, null, null, null, null);
    }
}
