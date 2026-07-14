package dev.counters.sdk;

/** A counter's current value. {@code value} is a signed arbitrary-precision integer as a decimal string. */
public record ValueResponse(String key, String value, long epoch) {}
