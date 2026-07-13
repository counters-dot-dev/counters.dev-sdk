package dev.counters.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in a {@code POST /batch} request. {@code op} is one of add/subtract/clear/delete; {@code amount}
 * is required for add/subtract and ignored otherwise. {@code occurredAt} (RFC 3339) buckets the op at event
 * time instead of ingest time (offline spools).
 */
public record Operation(String counterKey, String op, String amount, String idempotencyKey, String occurredAt) {

    /** JSON shape for the wire; null fields are omitted. */
    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counterKey", counterKey);
        m.put("op", op);
        if (amount != null) m.put("amount", amount);
        if (idempotencyKey != null) m.put("idempotencyKey", idempotencyKey);
        if (occurredAt != null) m.put("occurredAt", occurredAt);
        return m;
    }
}
