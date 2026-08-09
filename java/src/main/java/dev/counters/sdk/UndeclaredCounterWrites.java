package dev.counters.sdk;

/** Policy for ordinary writes to counter keys that have not been declared. */
public enum UndeclaredCounterWrites {
    /** Ordinary writes may implicitly create absent counters. */
    ALLOW("allow"),
    /** Ordinary writes reject absent counters until they are declared. */
    REJECT("reject");

    private final String wireValue;

    UndeclaredCounterWrites(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Lowercase value used by the HTTP API. */
    public String wireValue() {
        return wireValue;
    }

    static UndeclaredCounterWrites fromWire(String value) {
        if (value == null) {
            throw new CountersValidationException("response field undeclaredCounterWrites is required");
        }
        return switch (value) {
            case "allow" -> ALLOW;
            case "reject" -> REJECT;
            default -> throw new CountersValidationException(
                    "response field undeclaredCounterWrites is not `allow` or `reject`");
        };
    }
}
