package dev.counters.sdk;

/** Read parameters for a leaderboard page. Pass null fields to let the server apply defaults. */
public record LeaderboardParams(Integer limit, Integer offset, String order, Long epoch) {
    public LeaderboardParams() {
        this(null, null, null, null);
    }
}
