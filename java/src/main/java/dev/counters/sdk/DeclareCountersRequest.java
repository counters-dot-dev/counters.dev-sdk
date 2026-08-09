package dev.counters.sdk;

import java.util.List;

/** Atomic startup declaration request for the client's complete known counter set. */
public record DeclareCountersRequest(
        List<CounterDeclaration> counters,
        UndeclaredCounterWrites undeclaredCounterWrites) {}
