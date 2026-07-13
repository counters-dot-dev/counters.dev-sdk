package dev.counters.sdk;

/**
 * Thrown when no HTTP response was obtained: a network/connect error that persisted until retries
 * were exhausted. Unlike {@link CountersApiException} it carries no HTTP status (there was no
 * response). {@code cause} is the last underlying transport failure. This replaces the former
 * practice of surfacing exhausted-retry failures as a {@code CountersApiException} with status 0.
 */
public class CountersTransportException extends CountersException {

    public CountersTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
