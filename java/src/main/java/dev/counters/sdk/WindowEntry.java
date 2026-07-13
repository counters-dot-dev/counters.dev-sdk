package dev.counters.sdk;

/** One ranked entry in a trailing-window leaderboard. */
public record WindowEntry(long rank, String member, String value) {}
