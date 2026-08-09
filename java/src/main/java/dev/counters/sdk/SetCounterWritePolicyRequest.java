package dev.counters.sdk;

/** Compare-and-set request for the organization-wide implicit-create policy. */
public record SetCounterWritePolicyRequest(
        UndeclaredCounterWrites undeclaredCounterWrites,
        long expectedVersion) {}
