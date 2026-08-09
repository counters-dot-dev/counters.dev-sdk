package dev.counters.sdk;

import java.time.Instant;

/** Versioned organization policy for implicit counter creation. */
public record CounterWritePolicy(
        UndeclaredCounterWrites undeclaredCounterWrites,
        long version,
        boolean explicit,
        Instant updatedAt,
        String updatedBy) {}
