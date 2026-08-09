package dev.counters.sdk;

import java.util.List;

/** Successful atomic counter declaration response. */
public record DeclareCountersResponse(
        List<CounterDeclarationResult> results,
        CounterWritePolicy policy) {}
