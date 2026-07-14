package dev.counters.sdk;

/**
 * Catchable root for the SDK's three error kinds. Every SDK-originated failure is exactly one of
 * {@link CountersApiException}, {@link CountersTransportException}, or
 * {@link CountersValidationException}.
 */
public abstract sealed class CountersException extends RuntimeException
        permits CountersApiException, CountersTransportException, CountersValidationException {

    protected CountersException(String message) {
        super(message);
    }

    protected CountersException(String message, Throwable cause) {
        super(message, cause);
    }

    static CountersException normalizeBatchFailure(Throwable failure) {
        if (failure instanceof CountersException countersFailure) return countersFailure;
        return new CountersTransportException("unexpected batch submission failure", failure);
    }
}
