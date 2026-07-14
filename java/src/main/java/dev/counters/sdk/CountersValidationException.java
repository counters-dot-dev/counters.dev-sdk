package dev.counters.sdk;

/**
 * Thrown when an input is rejected before a request, or a parsed response cannot be represented by
 * the SDK's public types.
 */
public final class CountersValidationException extends CountersException {

    public CountersValidationException(String message) {
        super(message);
    }

    public CountersValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
