package dev.counters.sdk;

/**
 * Thrown when no usable HTTP response was obtained: a network/connect error that persisted until retries
 * were exhausted, or an injected transport that failed before exposing a valid HTTP status. Unlike
 * {@link CountersApiException} it carries no HTTP status. {@code cause} is the underlying transport
 * failure. This replaces the former practice of surfacing exhausted-retry failures as a
 * {@code CountersApiException} with status 0.
 */
public final class CountersTransportException extends CountersException {

    public CountersTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
