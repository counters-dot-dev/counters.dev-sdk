package dev.counters.sdk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in a {@code POST /batch} request. {@code operation} is one of add/subtract/clear/delete;
 * {@code amount}
 * is required for add/subtract and ignored otherwise. {@code occurredAt} buckets the operation at event
 * time instead of ingest time (offline spools).
 */
public record Operation(
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
