package dev.counters.sdk;

import java.math.BigInteger;
import java.time.OffsetDateTime;

/**
 * A typed handle to a single counter, obtained from {@link CountersClient#counter(String)} (which validates
 * the key). Amount arguments accept {@code long}, decimal-digit {@code String}, or {@link BigInteger} and are
 * normalised to a non-negative {@link BigInteger}; invalid amounts throw {@link CountersValidationException}.
 */
public final class CounterHandle {

    private final CountersClient client;
    private final String key;

    CounterHandle(CountersClient client, String key) {
        this.client = client;
        this.key = key;
    }

    /** The validated counter key. */
    public String key() {
        return key;
    }

    // ---- buffered writes (coalesced per counter; flushed in the background) ----

    /** Buffer an increment (flushed in the background; coalesced with other writes to this counter). */
    public void add(long amount) {
        client.enqueue(key, Validation.toAmount(amount));
    }

    /** Buffer an increment from a decimal-digit string. */
    public void add(String amount) {
        client.enqueue(key, Validation.toAmount(amount));
    }

    /** Buffer an increment. */
    public void add(BigInteger amount) {
        client.enqueue(key, Validation.toAmount(amount));
    }

    /** Buffer a decrement. The counter may go negative. */
    public void subtract(long amount) {
        client.enqueue(key, Validation.toAmount(amount).negate());
    }

    /** Buffer a decrement from a decimal-digit string. */
    public void subtract(String amount) {
        client.enqueue(key, Validation.toAmount(amount).negate());
    }

    /** Buffer a decrement. */
    public void subtract(BigInteger amount) {
        client.enqueue(key, Validation.toAmount(amount).negate());
    }

    // ---- immediate writes (return the new counter state) ----

    /** Apply an increment immediately and return the new counter state. */
    public Counter addNow(long amount) {
        return addNow(amount, null);
    }

    /** Apply an increment immediately, stamped with an event time (series bucket lands at {@code occurredAt}). */
    public Counter addNow(long amount, OffsetDateTime occurredAt) {
        return client.applyNow(key, "add", Validation.toAmount(amount), occurredAt);
    }

    /** Apply an increment immediately and return the new counter state. */
    public Counter addNow(String amount) {
        return addNow(amount, null);
    }

    /** Apply an increment immediately, stamped with an event time. */
    public Counter addNow(String amount, OffsetDateTime occurredAt) {
        return client.applyNow(key, "add", Validation.toAmount(amount), occurredAt);
    }

    /** Apply an increment immediately and return the new counter state. */
    public Counter addNow(BigInteger amount) {
        return addNow(amount, null);
    }

    /** Apply an increment immediately, stamped with an event time. */
    public Counter addNow(BigInteger amount, OffsetDateTime occurredAt) {
        return client.applyNow(key, "add", Validation.toAmount(amount), occurredAt);
    }

    /** Apply a decrement immediately and return the new counter state. */
    public Counter subtractNow(long amount) {
        return subtractNow(amount, null);
    }

    /** Apply a decrement immediately, stamped with an event time. */
    public Counter subtractNow(long amount, OffsetDateTime occurredAt) {
        return client.applyNow(key, "subtract", Validation.toAmount(amount), occurredAt);
    }

    /** Apply a decrement immediately and return the new counter state. */
    public Counter subtractNow(String amount) {
        return subtractNow(amount, null);
    }

    /** Apply a decrement immediately, stamped with an event time. */
    public Counter subtractNow(String amount, OffsetDateTime occurredAt) {
        return client.applyNow(key, "subtract", Validation.toAmount(amount), occurredAt);
    }

    /** Apply a decrement immediately and return the new counter state. */
    public Counter subtractNow(BigInteger amount) {
        return subtractNow(amount, null);
    }

    /** Apply a decrement immediately, stamped with an event time. */
    public Counter subtractNow(BigInteger amount, OffsetDateTime occurredAt) {
        return client.applyNow(key, "subtract", Validation.toAmount(amount), occurredAt);
    }

    // ---- lifecycle & reads ----

    /** Reset the counter to zero (starts a new epoch; history retained). */
    public Counter clear() {
        return client.clearCounter(key);
    }

    /** Delete (tombstone) the counter. */
    public void delete() {
        client.deleteCounter(key);
    }

    /** Current value. */
    public ValueResponse value() {
        return client.getValue(key);
    }

    /** Time series (delta per bucket). */
    public SeriesResponse series(SeriesParams params) {
        return client.getSeries(key, params);
    }

    /** One member's time series (delta per bucket). Requires member series enabled on the counter. */
    public MemberSeriesResponse memberSeries(String member, SeriesParams params) {
        Validation.assertMemberKey(member);
        return client.getMemberSeries(key, member, params);
    }

    /** Dense per-member multi-series. Requires member series enabled on the counter. */
    public MemberGroupSeriesResponse groupSeries(SeriesParams params) {
        return client.getGroupSeries(key, params);
    }

    /** The ranked member leaderboard for this counter, using server defaults. */
    public Leaderboard leaderboard() {
        return client.getLeaderboard(key, null);
    }

    /** The ranked member leaderboard for this counter. */
    public Leaderboard leaderboard(LeaderboardParams params) {
        return client.getLeaderboard(key, params);
    }

    /** The windowed leaderboard: members ranked by summed activity over the trailing window. */
    public WindowLeaderboard windowLeaderboard(WindowLeaderboardParams params) {
        return client.getWindowLeaderboard(key, params);
    }

    /** A typed handle for one member of this counter's board. */
    public MemberHandle member(String member) {
        Validation.assertMemberKey(member);
        return new MemberHandle(client, key, member);
    }
}
