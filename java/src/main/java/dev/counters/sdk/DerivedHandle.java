package dev.counters.sdk;

/** A typed handle to a server-defined, read-only derived counter. */
public final class DerivedHandle {

    private final CountersClient client;
    private final String key;

    DerivedHandle(CountersClient client, String key) {
        this.client = client;
        this.key = key;
    }

    /** The validated derived key. */
    public String key() {
        return key;
    }

    /** Evaluate the current value. {@code value()} may be null with a non-null {@code reason()}. */
    public DerivedValueResponse value() {
        return client.getDerivedValue(key);
    }

    /** Evaluate the derived expression per bucket over [from, to). */
    public DerivedSeriesResponse series(DerivedSeriesParams params) {
        return client.getDerivedSeries(key, params);
    }
}
