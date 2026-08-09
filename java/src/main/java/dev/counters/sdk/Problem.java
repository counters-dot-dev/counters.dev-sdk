package dev.counters.sdk;

/** RFC 9457-style error embedded in a per-key declaration result. */
public record Problem(String type, String title, Integer status, String detail, String instance) {}
