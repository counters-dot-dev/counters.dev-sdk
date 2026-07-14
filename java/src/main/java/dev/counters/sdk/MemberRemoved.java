package dev.counters.sdk;

/** The result of removing a member from the current board. */
public record MemberRemoved(String key, String member, long epoch, String value) {}
