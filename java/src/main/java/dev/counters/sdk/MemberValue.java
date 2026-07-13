package dev.counters.sdk;

/** A member's standing value after an immediate write. {@code value} is the sum-board total when present. */
public record MemberValue(
        String key,
        String member,
        String memberValue,
        boolean memberAccepted,
        String mode,
        long epoch,
        String value) {}
