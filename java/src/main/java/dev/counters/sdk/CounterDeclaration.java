package dev.counters.sdk;

/** Desired immutable settings for one counter in an atomic declaration. */
public record CounterDeclaration(String key, String memberMode, Boolean memberSeriesEnabled) {

    /** Declare an unconfigured scalar counter. */
    public CounterDeclaration(String key) {
        this(key, null, null);
    }

    /** Declare a counter with a member-board mode; member series defaults to disabled. */
    public CounterDeclaration(String key, String memberMode) {
        this(key, memberMode, null);
    }
}
