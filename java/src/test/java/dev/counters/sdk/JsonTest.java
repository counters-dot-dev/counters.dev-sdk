package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hand-rolled JSON layer: exact writer output and tolerant parsing of the known response shapes. */
class JsonTest {

    @Test
    void writesRequestShapes() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", "42");
        body.put("occurredAt", "2026-07-01T12:00:00Z");
        assertEquals("{\"amount\":\"42\",\"occurredAt\":\"2026-07-01T12:00:00Z\"}", Json.write(body));

        Map<String, Object> batch = Map.of("operations",
                List.of(new Operation("a", "add", "6", "k1", null).toJson()));
        assertEquals("{\"operations\":[{\"counterKey\":\"a\",\"op\":\"add\",\"amount\":\"6\",\"idempotencyKey\":\"k1\"}]}",
                Json.write(batch));
    }

    @Test
    void escapesStringsOnWrite() {
        String input = "a\"b\\c\n\t" + (char) 1;
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\"", Json.write(input));
    }

    @Test
    void parsesNestedResponseShapes() {
        Object v = Json.parse(" {\"a\": [1, -2, \"three\"], \"b\": {\"c\": true, \"d\": null}, \"e\": 2.5} ");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(List.of(1L, -2L, "three"), m.get("a"));
        assertEquals(true, ((Map<?, ?>) m.get("b")).get("c"));
        assertNull(((Map<?, ?>) m.get("b")).get("d"));
        assertEquals(2.5, (Double) m.get("e"), 1e-9);
    }

    @Test
    void parsesStringEscapesAndUnicode() {
        assertEquals("tab\there \"q\" \\ / \nnl é", Json.parse(
                "\"tab\\there \\\"q\\\" \\\\ \\/ \\nnl \\u00e9\""));
        assertEquals("emoji😀", Json.parse("\"emoji😀\""));
    }

    @Test
    void integersBeyondLongBecomeBigInteger() {
        assertEquals(new BigInteger("18446744073709551616"), Json.parse("18446744073709551616"));
        assertEquals(7L, Json.parse("7"));
        // Counter values arrive as JSON strings and stay strings — never lossy doubles.
        Map<?, ?> m = (Map<?, ?>) Json.parse("{\"value\":\"99999999999999999999999999999999\"}");
        assertEquals("99999999999999999999999999999999", m.get("value"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(CountersException.class, () -> Json.parse("{\"a\":"));
        assertThrows(CountersException.class, () -> Json.parse("[1,"));
        assertThrows(CountersException.class, () -> Json.parse("\"unterminated"));
        assertThrows(CountersException.class, () -> Json.parse("{a:1}"));
    }
}
