package dev.counters.sdk;

/** Base (unchecked) exception for all SDK failures. */
public class CountersException extends RuntimeException {

    public CountersException(String message) {
        super(message);
    }

    public CountersException(String message, Throwable cause) {
        super(message, cause);
    }

    static CountersException normalizeBatchFailure(Throwable failure) {
        if (failure instanceof CountersException countersFailure) return countersFailure;
        return new CountersTransportException("unexpected batch submission failure", failure);
    }
}
