package dev.counters.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the conformance vector file — objects, arrays, strings (with escapes),
 * integers (as {@link Long}), doubles, booleans, null. The SDK's own {@code dev.counters.sdk.Json}
 * is deliberately package-private (not SDK surface), so the example app carries its own ~60 lines
 * rather than growing the SDK's public API just to read a fixture.
 */
final class JsonMini {

    private final String s;
    private int i;

    private JsonMini(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        JsonMini p = new JsonMini(text);
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw new IllegalArgumentException("trailing JSON at offset " + p.i);
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private void literal(String lit) {
        if (!s.startsWith(lit, i)) throw new IllegalArgumentException("bad literal at offset " + i);
        i += lit.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // '{'
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            if (s.charAt(i++) != ':') throw new IllegalArgumentException("expected ':' at offset " + (i - 1));
            m.put(key, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at offset " + (i - 1));
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++; // '['
        ws();
        if (s.charAt(i) == ']') { i++; return list; }
        while (true) {
            list.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return list;
            if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at offset " + (i - 1));
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at offset " + i);
        StringBuilder sb = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(start, i);
        return (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0)
                ? (Object) Double.parseDouble(t)
                : (Object) Long.parseLong(t);
    }
}
