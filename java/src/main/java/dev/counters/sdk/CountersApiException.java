package dev.counters.sdk;

/**
 * Thrown for terminal non-2xx API responses, and for a 2xx response whose body could not be parsed.
 * {@code status} is always the real HTTP status code of that response; {@code title} is the RFC 9457
 * {@code application/problem+json} title when the server provided one.
 * A failure that never produced an HTTP response (retries exhausted on connect errors) is a
 * {@link CountersTransportException}, not this type with status 0.
 */
public final class CountersApiException extends CountersException {

    private final int status;
    private final String title;

    public CountersApiException(int status, String title) {
        this(status, title, null);
    }

    public CountersApiException(int status, String title, Throwable cause) {
        super("counters: HTTP " + status + ": " + title, cause);
        this.status = status;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }
}
