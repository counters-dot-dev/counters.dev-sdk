package dev.counters.sdk;

/** Read-only operations available for a counter scoped to a publishable ({@code pk_}) token. */
public interface ReadOnlyCounterHandle {

    /** The validated counter key. */
    String key();

    /** Current value. */
    ValueResponse value();

    /** Time series (delta per bucket). */
    SeriesResponse series(SeriesParams params);

    /** One member's time series (delta on a sum board; sparse scores on a score board). Requires member series enabled. */
    MemberSeriesResponse memberSeries(String member, SeriesParams params);

    /** The per-member multi-series (dense on a sum board, sparse on a score board). Requires member series enabled. */
    MemberGroupSeriesResponse groupSeries(SeriesParams params);

    /** The ranked member leaderboard using server defaults. */
    Leaderboard leaderboard();

    /** The ranked member leaderboard. */
    Leaderboard leaderboard(LeaderboardParams params);

    /** Members ranked by activity over a trailing window. */
    WindowLeaderboard windowLeaderboard(WindowLeaderboardParams params);

    /** A read-only handle for one member of this counter's board. */
    ReadOnlyMemberHandle member(String member);
}
