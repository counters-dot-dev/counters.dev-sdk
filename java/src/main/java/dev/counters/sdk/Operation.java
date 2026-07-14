package dev.counters.sdk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in a {@code POST /batch} request. {@code operation} is one of add/subtract/clear/delete;
 * {@code amount}
 * is required for add/subtract and ignored otherwise. {@code occurredAt} buckets the operation at event
 * time instead of ingest time (offline spools).
 *
 * <p>Deliberately package-private: no public SDK method accepts or returns a batch operation, and
 * publishing it would freeze a dead-end shape (the spec's Operation also carries member-write
 * fields this SDK never sends). Widen deliberately if a public batch API ever ships.
 */
record Operation(
        String counterKey,
        String operation,
        String amount,
        String idempotencyKey,
        Instant occurredAt) {

    /** JSON shape for the wire; null fields are omitted. */
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counterKey", counterKey);
        m.put("op", operation);
        if (amount != null) m.put("amount", amount);
        if (idempotencyKey != null) m.put("idempotencyKey", idempotencyKey);
        if (occurredAt != null) m.put("occurredAt", occurredAt.toString());
        return m;
    }
}
