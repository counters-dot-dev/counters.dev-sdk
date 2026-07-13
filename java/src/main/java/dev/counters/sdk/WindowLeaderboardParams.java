package dev.counters.sdk;

/** Read parameters for a windowed leaderboard. */
public record WindowLeaderboardParams(String window, Integer limit, Integer offset, String order, Long epoch) {
    public WindowLeaderboardParams {
        Validation.assertWindow(window);
    }

    public WindowLeaderboardParams(String window) {
        this(window, null, null, null, null);
    }
}
