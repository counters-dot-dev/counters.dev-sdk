package dev.counters.sdk;

import java.util.Map;

/**
 * The evaluated value of a derived counter. {@code value} is a decimal string, or null with
 * {@code reason} when the expression divided by zero.
 */
public record DerivedValueResponse(String key, String value, long scale, Map<String, String> inputs, String reason) {}
