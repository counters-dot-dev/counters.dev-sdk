package dev.counters.sdk;

/**
 * Read-only client surface for a scoped publishable ({@code pk_}) token.
 *
 * <p>Obtain one from {@link CountersClient#publishableBuilder()}. Organization-wide reads and every
 * write operation are deliberately absent from this type.
 */
public interface ReadOnlyCountersClient extends AutoCloseable {

    /** Get a read-only handle for a counter. Throws {@link CountersValidationException} for an invalid key. */
    ReadOnlyCounterHandle counter(String key);

    /** Release this client. A read-only client has no buffered writes to flush. */
    @Override
    void close();
}
