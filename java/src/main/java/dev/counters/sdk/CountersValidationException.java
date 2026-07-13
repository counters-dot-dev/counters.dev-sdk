package dev.counters.sdk;

/** Thrown for client-side validation failures (bad counter key, bad amount) before any request is made. */
public class CountersValidationException extends CountersException {

    public CountersValidationException(String message) {
        super(message);
    }
}
