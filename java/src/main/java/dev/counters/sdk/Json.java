package dev.counters.sdk;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON, so the SDK stays zero-dependency.
 *
 * <p>The writer covers exactly what requests need (maps/lists of strings). The parser is a small tolerant
 * recursive-descent reader for the known response shapes: nested objects/arrays, strings (with escapes),
 * numbers, booleans, null. Counter values/amounts arrive as JSON <em>strings</em> and are kept as
 * {@link String}s — precision is never routed through a double.
 */
final class Json {

    private Json() {}

    // ---- writer ----

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(s, sb);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof BigInteger) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                writeValue(o, sb);
            }
            sb.append(']');
        } else {
            throw new CountersValidationException("cannot serialize " + v.getClass().getName() + " to JSON");
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- parser ----

    /**
     * Parse a JSON document into {@code Map<String,Object>} / {@code List<Object>} / {@code String} /
     * {@code Long} or {@code BigInteger} (integers) / {@code Double} (decimals) / {@code Boolean} / null.
     */
    static Object parse(String text) {
        try {
            Parser p = new Parser(text);
            Object v = p.parseValue();
            p.skipWs(); // tolerant: trailing content is ignored
            return v;
        } catch (CountersValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CountersValidationException("invalid JSON", e);
        }
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            if (i >= s.length()) throw err("unexpected end of input");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                m.put(key, parseValue());
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}' in object");
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw err("expected ',' or ']' in array");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw err("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("truncated \\u escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("invalid escape '\\" + e + "'");
                }
            }
        }

        private Object parseNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && isDigit(s.charAt(i))) i++;
            boolean integral = true;
            if (i < s.length() && s.charAt(i) == '.') {
                integral = false;
                i++;
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                integral = false;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            if (num.isEmpty() || num.equals("-")) throw err("invalid number");
            if (!integral) return Double.parseDouble(num);
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return new BigInteger(num);
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) throw err("invalid literal");
            i += word.length();
            return value;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private char peek() {
            if (i >= s.length()) throw err("unexpected end of input");
            return s.charAt(i);
        }

        private char next() {
            if (i >= s.length()) throw err("unexpected end of input");
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (next() != c) throw err("expected '" + c + "'");
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private CountersValidationException err(String msg) {
            return new CountersValidationException("invalid JSON at offset " + i + ": " + msg);
        }
    }
}
