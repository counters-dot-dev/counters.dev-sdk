package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** End-to-end client tests against a local {@code com.sun.net.httpserver.HttpServer} (the JDK's httptest). */
class ClientTest {

    private static final String UUID_V4 =
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    /** One recorded request. */
    record Recorded(String method, String path, String query, Map<String, String> headers, String body) {}

    private HttpServer server;
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Start a server that records every request and delegates the response to {@code responder}. */
    private String startServer(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        HttpHandler handler = exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : List.of("Authorization", "Idempotency-Key", "Content-Type")) {
                String v = exchange.getRequestHeaders().getFirst(name);
                if (v != null) headers.put(name, v);
            }
            Recorded r = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), headers, body);
            recorded.add(r);
            responder.respond(exchange, r);
        };
        server.createContext("/", handler);
        server.start();
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/v1";
    }

    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, Recorded request) throws IOException;
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> q = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return q;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            q.put(k, v);
        }
        return q;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(String body) {
        return (Map<String, Object>) Json.parse(body);
    }

    private CountersClient client(String baseUrl) {
        return CountersClient.builder().apiKey("secret").baseUrl(baseUrl).backoffMillis(1).build();
    }

    // ---- addNow: request shape, headers, response parsing ----

    @Test
    void addNowSendsShapeAndParsesCounter() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200, "{\"key\":\"c\",\"value\":\"5\",\"epoch\":0}"));
        try (CountersClient c = client(baseUrl)) {
            Counter counter = c.counter("c").addNow(5);

            assertEquals("c", counter.key());
            assertEquals("5", counter.value());
            assertEquals(0, counter.epoch());
            assertNull(counter.createdAt(), "absent optional createdAt must stay null");
            assertNull(counter.updatedAt(), "absent optional updatedAt must stay null");

            Recorded r = recorded.get(0);
            assertEquals("POST", r.method());
            assertEquals("/v1/counters/c/add", r.path());
            assertEquals("Bearer secret", r.headers().get("Authorization"));
            assertEquals("application/json", r.headers().get("Content-Type"));
            String idem = r.headers().get("Idempotency-Key");
            assertNotNull(idem, "missing Idempotency-Key header");
            assertTrue(idem.matches(UUID_V4), "Idempotency-Key must be a v4 UUID, got: " + idem);

            Map<String, Object> body = parseBody(r.body());
            assertEquals("5", body.get("amount"));
            assertFalse(body.containsKey("occurredAt"), "plain addNow must not send occurredAt");
        }
    }

    @Test
    void addNowForwardsOccurredAtAndOmitsItWhenAbsent() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200, "{\"key\":\"c\",\"value\":\"1\",\"epoch\":0}"));
        try (CountersClient c = client(baseUrl)) {
            Instant at = Instant.parse("2026-07-01T12:00:00Z");
            c.counter("c").addNow(1, at);
            c.counter("c").addNow(1);
            c.counter("c").subtractNow("2", at);

            Map<String, Object> withAt = parseBody(recorded.get(0).body());
            assertEquals("2026-07-01T12:00:00Z", withAt.get("occurredAt"));
            assertEquals("1", withAt.get("amount"));

            Map<String, Object> without = parseBody(recorded.get(1).body());
            assertFalse(without.containsKey("occurredAt"), "plain addNow must not send occurredAt");

            Recorded sub = recorded.get(2);
            assertEquals("/v1/counters/c/subtract", sub.path());
            assertEquals("2026-07-01T12:00:00Z", parseBody(sub.body()).get("occurredAt"));
        }
    }

    @Test
    void valueParses() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200, "{\"key\":\"c\",\"value\":\"-5\",\"epoch\":2}"));
        try (CountersClient c = client(baseUrl)) {
            ValueResponse v = c.counter("c").value();
            assertEquals("GET", recorded.get(0).method());
            assertEquals("/v1/counters/c/value", recorded.get(0).path());
            assertEquals("c", v.key());
            assertEquals("-5", v.value());
            assertEquals(2, v.epoch());
        }
    }

    @Test
    void seriesSendsQueryParamsAndParsesResponse() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"counterKey\":\"c\",\"bucket\":\"1h\",\"mode\":\"delta\",\"tz\":\"Europe/London\","
                        + "\"range\":{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"},"
                        + "\"points\":[{\"t\":\"2026-01-01T00:00:00Z\",\"v\":\"7\"}]}"));
        try (CountersClient c = client(baseUrl)) {
            Instant from = Instant.parse("2026-01-01T00:00:00Z");
            Instant to = Instant.parse("2026-01-02T00:00:00Z");
            SeriesResponse s = c.counter("c").series(new SeriesParams(from, to, "1h", "Europe/London", true));

            assertEquals("/v1/counters/c/series", recorded.get(0).path());
            Map<String, String> q = parseQuery(recorded.get(0).query());
            assertEquals("2026-01-01T00:00:00Z", q.get("from"));
            assertEquals("2026-01-02T00:00:00Z", q.get("to"));
            assertEquals("1h", q.get("bucket"));
            assertEquals("Europe/London", q.get("tz"));
            assertEquals("true", q.get("gapfill"));

            assertEquals("c", s.counterKey());
            assertEquals("delta", s.mode());
            assertEquals("Europe/London", s.timeZone());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.range().from());
            assertEquals(1, s.points().size());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.points().get(0).timestamp());
            assertEquals("7", s.points().get(0).value());

            // Optional params are omitted, not sent empty.
            c.counter("c").series(new SeriesParams(from, to, "1d"));
            Map<String, String> q2 = parseQuery(recorded.get(1).query());
            assertEquals("1d", q2.get("bucket"));
            assertFalse(q2.containsKey("tz"));
            assertFalse(q2.containsKey("gapfill"));
        }
    }

    @Test
    void seriesSurfacesBigValuesAsExactStrings() throws IOException {
        String huge = "100000000000000000000000000000000"; // > 2^64, from conformance/bignum.json
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"counterKey\":\"c\",\"bucket\":\"1h\",\"mode\":\"delta\","
                        + "\"range\":{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"},"
                        + "\"points\":[{\"t\":\"2026-01-01T00:00:00Z\",\"v\":\"" + huge + "\"}]}"));
        try (CountersClient c = client(baseUrl)) {
            Instant from = Instant.parse("2026-01-01T00:00:00Z");
            Instant to = Instant.parse("2026-01-02T00:00:00Z");
            SeriesResponse s = c.counter("c").series(new SeriesParams(from, to, "1h"));

            assertEquals(1, s.points().size());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.points().get(0).timestamp());
            assertEquals(huge, s.points().get(0).value(),
                    "series point must round-trip as a string, no precision loss");
        }
    }

    /**
     * Pins the series read path to the shared {@code conformance/series/cases.json} vectors (B8/B9):
     * series params -> exact query encoding (presence-exact), and a SeriesResponse body -> parsed points.
     */
    @Test
    @SuppressWarnings("unchecked")
    void seriesConformanceVectors() throws IOException {
        Map<String, Object> v = ConformanceTest.loadVectors("series/cases.json");

        for (Object rawCase : (List<?>) v.get("query")) {
            Map<String, Object> c = (Map<String, Object>) rawCase;
            String name = (String) c.get("name");
            Map<String, Object> p = (Map<String, Object>) c.get("params");
            if (c.containsKey("expect")) {
                // N/A in Java: member and groupBy are separate methods, so the invalid combined shape
                // cannot be expressed by the public API.
                continue;
            }
            Map<String, Object> want = (Map<String, Object>) c.get("query");

            recorded.clear();
            String bucket = (String) p.get("bucket");
            String baseUrl = startServer((ex, r) -> json(ex, 200,
                    "{\"counterKey\":\"c\",\"bucket\":\"" + bucket + "\",\"mode\":\"delta\","
                            + "\"range\":{\"from\":\"" + p.get("from") + "\",\"to\":\""
                            + p.get("to") + "\"},\"points\":[]}"));
            try (CountersClient c2 = client(baseUrl)) {
                Instant from = Instant.parse((String) p.get("from"));
                Instant to = Instant.parse((String) p.get("to"));
                String timeZone = (String) p.get("tz");
                Boolean gapfill = (Boolean) p.get("gapfill");
                String mode = (String) p.get("mode");
                SeriesParams params = new SeriesParams(from, to, bucket, mode, timeZone, gapfill);
                if (p.get("member") instanceof String member) {
                    c2.counter("c").memberSeries(member, params);
                } else if ("member".equals(p.get("groupBy"))) {
                    c2.counter("c").groupSeries(params);
                } else {
                    c2.counter("c").series(params);
                }
                Map<String, String> q = parseQuery(recorded.get(0).query());
                // presence-exact: every listed key present with that value, and nothing else on the wire.
                assertEquals(want.keySet(), q.keySet(), "query keys for case " + name);
                for (Map.Entry<String, Object> e : want.entrySet()) {
                    assertEquals(e.getValue(), q.get(e.getKey()), name + ": " + e.getKey());
                }
            }
            stopServer();
        }

        for (Object rawCase : (List<?>) v.get("parse")) {
            Map<String, Object> c = (Map<String, Object>) rawCase;
            String name = (String) c.get("name");
            String kind = (String) c.get("kind");
            Map<String, Object> body = (Map<String, Object>) c.get("body");
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");

            String baseUrl = startServer((ex, r) -> json(ex, 200, Json.write(body)));
            try (CountersClient c2 = client(baseUrl)) {
                Map<String, Object> range = (Map<String, Object>) body.get("range");
                Instant from = Instant.parse((String) range.get("from"));
                Instant to = Instant.parse((String) range.get("to"));
                SeriesParams params = new SeriesParams(from, to, (String) body.get("bucket"));
                if ("memberSeries".equals(kind)) {
                    MemberSeriesResponse s = c2.counter((String) body.get("counterKey"))
                            .memberSeries((String) body.get("member"), params);
                    assertEquals(expect.get("counterKey"), s.counterKey(), name);
                    assertEquals(expect.get("member"), s.member(), name);
                    assertEquals(expect.get("bucket"), s.bucket(), name);
                    assertEquals(expect.get("mode"), s.mode(), name);
                    assertPoints((List<?>) expect.get("points"), s.points(), name);
                } else if ("memberGroupSeries".equals(kind)) {
                    MemberGroupSeriesResponse s = c2.counter((String) body.get("counterKey"))
                            .groupSeries(params);
                    assertEquals(expect.get("counterKey"), s.counterKey(), name);
                    assertEquals(expect.get("bucket"), s.bucket(), name);
                    List<?> expectedSeries = (List<?>) expect.get("series");
                    assertEquals(expectedSeries.size(), s.series().size(), name);
                    for (int i = 0; i < expectedSeries.size(); i++) {
                        Map<String, Object> es = (Map<String, Object>) expectedSeries.get(i);
                        MemberSeriesEntry got = s.series().get(i);
                        assertEquals(es.get("member"), got.member(), name);
                        assertPoints((List<?>) es.get("points"), got.points(), name);
                    }
                } else {
                    SeriesResponse s = c2.counter((String) body.get("counterKey")).series(params);
                    assertEquals(expect.get("counterKey"), s.counterKey(), name);
                    assertEquals(expect.get("bucket"), s.bucket(), name);
                    assertEquals(expect.get("mode"), s.mode(), name);
                    assertPoints((List<?>) expect.get("points"), s.points(), name);
                }
            }
            stopServer();
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertPoints(List<?> expected, List<SeriesPoint> actual, String name) {
        assertEquals(expected.size(), actual.size(), name);
        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> ep = (Map<String, Object>) expected.get(i);
            assertEquals(Instant.parse((String) ep.get("t")), actual.get(i).timestamp(), name);
            // Delta stays a string (arbitrary precision).
            assertEquals(ep.get("v"), actual.get(i).value(), name);
        }
    }

    @Test
    void seriesRejectsUnknownBucket() {
        Instant t = Instant.now();
        assertThrows(CountersValidationException.class, () -> new SeriesParams(t.minusSeconds(3600), t, "2h"));
    }

    // ---- retry & error mapping ----

    @Test
    void retriesOn429ThenSucceeds() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        String baseUrl = startServer((ex, r) -> {
            if (attempts.incrementAndGet() < 3) empty(ex, 429);
            else json(ex, 200, "{\"key\":\"c\",\"value\":\"1\",\"epoch\":0}");
        });
        try (CountersClient c = client(baseUrl)) {
            Counter counter = c.counter("c").addNow(1);
            assertEquals("1", counter.value());
            assertEquals(3, attempts.get(), "expected two retries then success");
            // The idempotency key must be identical across retries so the server can de-dup.
            assertEquals(recorded.get(0).headers().get("Idempotency-Key"),
                    recorded.get(2).headers().get("Idempotency-Key"));
        }
    }

    @Test
    void terminalErrorMapsToApiExceptionWithoutRetry() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        String baseUrl = startServer((ex, r) -> {
            attempts.incrementAndGet();
            byte[] bytes = "{\"type\":\"about:blank\",\"title\":\"bad\",\"status\":400}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/problem+json");
            ex.sendResponseHeaders(400, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        try (CountersClient c = client(baseUrl)) {
            CountersApiException e = assertThrows(CountersApiException.class, () -> c.counter("c").addNow(1));
            assertEquals(400, e.status());
            assertEquals("bad", e.title());
            assertEquals(1, attempts.get(), "4xx (non-429) must not retry");
        }
    }

    @Test
    void retriesAreExhaustedOnPersistent503() throws IOException {
        AtomicInteger attempts = new AtomicInteger();
        String baseUrl = startServer((ex, r) -> {
            attempts.incrementAndGet();
            json(ex, 503, "{\"title\":\"unavailable\"}");
        });
        try (CountersClient c = CountersClient.builder()
                .apiKey("k").baseUrl(baseUrl).backoffMillis(1).maxRetries(2).build()) {
            CountersApiException e = assertThrows(CountersApiException.class, () -> c.counter("c").addNow(1));
            assertEquals(503, e.status());
            assertEquals("unavailable", e.title());
            assertEquals(3, attempts.get(), "maxRetries=2 -> 3 attempts");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorTaxonomyConformance() throws IOException {
        // B9: conformance/errors/cases.json driven through the real client (retries disabled).
        Map<String, Object> vectors = ConformanceTest.loadVectors("errors/cases.json");

        for (Object item : (List<?>) vectors.get("api")) {
            Map<String, Object> c = (Map<String, Object>) item;
            String name = (String) c.get("name");
            Map<String, Object> resp = (Map<String, Object>) c.get("response");
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            int status = ((Number) resp.get("status")).intValue();
            Object body = resp.get("body");
            String baseUrl = startServer((ex, r) -> {
                if (body == null) {
                    empty(ex, status);
                } else {
                    json(ex, status, Json.write(body));
                }
            });
            try (CountersClient client = CountersClient.builder()
                    .apiKey("k").baseUrl(baseUrl).backoffMillis(1).maxRetries(0).build()) {
                CountersApiException e = assertThrows(CountersApiException.class,
                        () -> client.counter("c").addNow(1), "api/" + name);
                assertTrue(e instanceof CountersException, "api/" + name + ": not a CountersException");
                assertEquals(((Number) expect.get("status")).intValue(), e.status(), "api/" + name + " status");
                if (expect.get("title") instanceof String title) {
                    assertTrue(e.getMessage().contains(title), "api/" + name + " title in message");
                }
            }
            stopServer();
        }

        for (Object item : (List<?>) vectors.get("transport")) {
            Map<String, Object> c = (Map<String, Object>) item;
            String name = (String) c.get("name");
            // Bind then never start: the port is not listening, so no HTTP response is ever obtained.
            HttpServer dead = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            String baseUrl = "http://127.0.0.1:" + dead.getAddress().getPort() + "/v1";
            try (CountersClient client = CountersClient.builder()
                    .apiKey("k").baseUrl(baseUrl).backoffMillis(1).maxRetries(0).build()) {
                CountersException e = assertThrows(CountersTransportException.class,
                        () -> client.counter("c").addNow(1), "transport/" + name);
                assertFalse(e instanceof CountersApiException, "transport/" + name + " must not be an API exception");
            }
        }

        for (Object item : (List<?>) vectors.get("validation")) {
            Map<String, Object> c = (Map<String, Object>) item;
            String name = (String) c.get("name");
            Map<String, Object> v = (Map<String, Object>) c.get("validate");
            if (v.get("key") instanceof String key) {
                try (CountersClient vc = CountersClient.builder().apiKey("k").baseUrl("https://x/v1").build()) {
                    assertThrows(CountersValidationException.class, () -> vc.counter(key), "validation/" + name);
                }
            } else {
                String amount = (String) v.get("amount");
                assertThrows(CountersValidationException.class,
                        () -> Validation.toAmount(amount), "validation/" + name);
            }
        }

        // batch[]: an outer HTTP 200 whose results[] carry a per-op "error" (
        // 2026-07-06, part A). A per-op problem with a status surfaces as the api type carrying it; a
        // problem with no status (or no problem object at all) surfaces as the validation type — never
        // an api exception with a fabricated status.
        for (Object item : (List<?>) vectors.get("batch")) {
            Map<String, Object> c = (Map<String, Object>) item;
            String name = (String) c.get("name");
            Map<String, Object> resp = (Map<String, Object>) c.get("response");
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            int status = ((Number) resp.get("status")).intValue();
            String body = Json.write(resp.get("body"));
            String baseUrl = startServer((ex, r) -> json(ex, status, body));
            try (CountersClient client = CountersClient.builder()
                    .apiKey("k").baseUrl(baseUrl).batchIntervalMillis(0).maxRetries(0).build()) {
                client.counter("a").add(1);
                if ("api".equals(expect.get("taxonomy"))) {
                    CountersApiException e = assertThrows(CountersApiException.class,
                            client::flush, "batch/" + name);
                    assertEquals(((Number) expect.get("status")).intValue(), e.status(),
                            "batch/" + name + " status");
                    if (expect.get("title") instanceof String title) {
                        assertTrue(e.getMessage().contains(title), "batch/" + name + " title in message");
                    }
                } else {
                    CountersException e = assertThrows(CountersValidationException.class,
                            client::flush, "batch/" + name);
                    assertFalse(e instanceof CountersApiException,
                            "batch/" + name + " must not be an API exception");
                }
            }
            stopServer();
        }
    }

    @Test
    void noResponseMapsToTransportExceptionNotStatusZero() throws IOException {
        // Bind then release a port so nothing accepts: every attempt is a connect failure (no response).
        HttpServer dead = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String baseUrl = "http://127.0.0.1:" + dead.getAddress().getPort() + "/v1";
        // dead was never start()ed, so the port is not listening.
        try (CountersClient c = CountersClient.builder()
                .apiKey("k").baseUrl(baseUrl).backoffMillis(1).maxRetries(1).build()) {
            // B2: no HTTP response ever arrived -> transport exception, still a CountersException,
            // and NOT a CountersApiException with a synthetic status 0.
            CountersException e =
                    assertThrows(CountersTransportException.class, () -> c.counter("c").addNow(1));
            assertFalse(e instanceof CountersApiException, "transport failure must not be an API exception");
            assertNotNull(e.getCause(), "transport exception should carry the underlying cause");
        }
    }

    // ---- buffered writes over HTTP ----

    @Test
    void bufferedAddsCoalesceIntoOneBatchRequest() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200, "{\"results\":[]}"));
        try (CountersClient c = CountersClient.builder()
                .apiKey("k").baseUrl(baseUrl).batchIntervalMillis(0).build()) {
            CounterHandle reg = c.counter("registrations");
            reg.add(1);
            reg.add(2);
            reg.add(3);
            c.flush();

            assertEquals(1, recorded.size(), "expected one coalesced batch request");
            Recorded r = recorded.get(0);
            assertEquals("POST", r.method());
            assertEquals("/v1/batch", r.path());

            Map<String, Object> body = parseBody(r.body());
            List<?> ops = (List<?>) body.get("operations");
            assertEquals(1, ops.size());
            Map<?, ?> op = (Map<?, ?>) ops.get(0);
            assertEquals("registrations", op.get("counterKey"));
            assertEquals("add", op.get("op"));
            assertEquals("6", op.get("amount"));
            assertTrue(((String) op.get("idempotencyKey")).matches(UUID_V4));
        }
    }

    @Test
    void retryBackoffGrowsExponentially() throws IOException {
        String baseUrl = startServer((ex, r) -> empty(ex, 503)); // always retryable, no Retry-After
        java.util.List<Long> delays = new java.util.ArrayList<>();
        Http http = new Http(baseUrl, "k", null, 3, 100, 30_000);
        http.setSleeper(delays::add);
        assertThrows(CountersApiException.class, () -> http.request("GET", "/counters", null, null, null));
        assertEquals(List.of(100L, 200L, 400L), delays); // exponential, not linear/constant
    }

    @Test
    void retryAfterHeaderIsHonored() throws IOException {
        AtomicInteger n = new AtomicInteger();
        String baseUrl = startServer((ex, r) -> {
            if (n.incrementAndGet() == 1) {
                ex.getResponseHeaders().set("Retry-After", "2");
                empty(ex, 503);
            } else {
                json(ex, 200, "{\"key\":\"c\",\"value\":\"1\",\"epoch\":0}");
            }
        });
        java.util.List<Long> delays = new java.util.ArrayList<>();
        Http http = new Http(baseUrl, "k", null, 3, 0, 30_000);
        http.setSleeper(delays::add);
        http.request("POST", "/counters/c/add", Map.of("amount", "1"), "idem", null);
        assertEquals(List.of(2000L), delays); // honored the header, not the (zeroed) exponential
    }

    @Test
    void hostileQueryEncoding() throws IOException {
        java.util.concurrent.atomic.AtomicReference<String> rawQuery = new java.util.concurrent.atomic.AtomicReference<>();
        String baseUrl = startServer((ex, r) -> {
            rawQuery.set(ex.getRequestURI().getRawQuery());
            json(ex, 200, "{\"data\":[]}");
        });
        try (CountersClient c = client(baseUrl)) {
            c.list("a&b=c#d e", null); // hostile cursor
        }
        String raw = rawQuery.get();
        assertTrue(raw.contains("%26") && raw.contains("%23"), "reserved chars must be percent-escaped: " + raw);
    }

    @Test
    void batchPerOpErrorIsSurfaced() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"results\":[{\"counterKey\":\"a\",\"status\":\"applied\",\"value\":\"1\"},"
                        + "{\"counterKey\":\"b\",\"status\":\"error\","
                        + "\"error\":{\"title\":\"counter limit reached\",\"status\":403}}]}"));
        try (CountersClient c = CountersClient.builder()
                .apiKey("k").baseUrl(baseUrl).batchIntervalMillis(0).build()) {
            c.counter("a").add(1);
            c.counter("b").add(1);
            CountersApiException e = assertThrows(CountersApiException.class, c::flush);
            assertEquals(403, e.status());
        }
    }

    @Test
    void bigIntegerAmountsSurviveTheWire() throws IOException {
        String huge = "100000000000000000000000000000000"; // > 2^63, from conformance/bignum.json
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"c\",\"value\":\"" + huge + "\",\"epoch\":0}"));
        try (CountersClient c = client(baseUrl)) {
            Counter counter = c.counter("c").addNow(huge);
            assertEquals(huge, counter.value(), "value must round-trip as a string, no precision loss");
            assertEquals(huge, parseBody(recorded.get(0).body()).get("amount"));
        }
    }

    // ---- clear / delete / list ----

    @Test
    void clearPostsAndParsesNewEpoch() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200, "{\"key\":\"c\",\"value\":\"0\",\"epoch\":3}"));
        try (CountersClient c = client(baseUrl)) {
            Counter counter = c.counter("c").clear();
            assertEquals("/v1/counters/c/clear", recorded.get(0).path());
            assertEquals("POST", recorded.get(0).method());
            assertNotNull(recorded.get(0).headers().get("Idempotency-Key"));
            assertEquals("0", counter.value());
            assertEquals(3, counter.epoch());
        }
    }

    @Test
    void deleteSends204NoBody() throws IOException {
        String baseUrl = startServer((ex, r) -> empty(ex, 204));
        try (CountersClient c = client(baseUrl)) {
            c.counter("c").delete();
            assertEquals("DELETE", recorded.get(0).method());
            assertEquals("/v1/counters/c", recorded.get(0).path());
            assertNotNull(recorded.get(0).headers().get("Idempotency-Key"));
        }
    }

    @Test
    void listSendsPaginationAndParsesPage() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"data\":[{\"key\":\"a\",\"value\":\"1\",\"epoch\":0,"
                        + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"2026-01-01T00:00:01Z\"},"
                        + "{\"key\":\"b\",\"value\":\"-2\",\"epoch\":1}],\"nextCursor\":\"n1\"}"));
        try (CountersClient c = client(baseUrl)) {
            CounterPage page = c.list("abc", 2);
            Map<String, String> q = parseQuery(recorded.get(0).query());
            assertEquals("/v1/counters", recorded.get(0).path());
            assertEquals("abc", q.get("cursor"));
            assertEquals("2", q.get("limit"));
            assertEquals(2, page.data().size());
            assertEquals("a", page.data().get(0).key());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), page.data().get(0).createdAt());
            assertEquals(Instant.parse("2026-01-01T00:00:01Z"), page.data().get(0).updatedAt());
            assertEquals("-2", page.data().get(1).value());
            assertNull(page.data().get(1).createdAt(), "absent optional createdAt must stay null");
            assertNull(page.data().get(1).updatedAt(), "absent optional updatedAt must stay null");
            assertEquals("n1", page.nextCursor());

            CounterPage first = c.list();
            assertNull(recorded.get(1).query(), "bare list() must not send query params");
            assertEquals(2, first.data().size());
        }
    }

    // ---- validation & construction ----

    @Test
    void counterRejectsInvalidKeys() {
        CountersClient c = CountersClient.builder().apiKey("k").build();
        assertThrows(CountersValidationException.class, () -> c.counter("has space"));
        assertThrows(CountersValidationException.class, () -> c.counter(""));
        assertEquals("ok.key", c.counter("ok.key").key());
        assertEquals("ns:metric", c.counter("ns:metric").key());
    }

    @Test
    void immediateModeRoutesErrorsToOnBatchError() throws Exception {
        String base = startServer((exchange, r) ->
                json(exchange, 403, "{\"title\":\"quota exceeded\",\"status\":403}"));
        java.util.concurrent.BlockingQueue<CountersException> errors =
                new java.util.concurrent.LinkedBlockingQueue<>();
        try (CountersClient client = CountersClient.builder()
                .apiKey("k").baseUrl(base).maxRetries(0)
                .batchEnabled(false)
                .onBatchError(errors::add)
                .build()) {
            client.counter("c").add(1);
            CountersException e = errors.poll(2, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(e, "immediate-mode write failure never reached onBatchError");
            assertTrue(e instanceof CountersApiException, "got " + e);
            assertEquals(403, ((CountersApiException) e).status());
        }
    }

    @Test
    void immediateModeRejectsWritesAfterClose() throws Exception {
        String base = startServer((exchange, r) -> json(exchange, 200, "{\"results\":[]}"));
        CountersClient client = CountersClient.builder()
                .apiKey("k").baseUrl(base).batchEnabled(false).build();
        CounterHandle c = client.counter("c");
        client.close();
        assertThrows(CountersException.class, () -> c.add(1));
    }

    @Test
    void requestTimeoutIsConfigurableAndSurfacesAsTransport() throws Exception {
        String base = startServer((exchange, r) -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(exchange, 200, "{}");
        });
        CountersClient client = CountersClient.builder()
                .apiKey("k").baseUrl(base).maxRetries(0).requestTimeoutMillis(50).build();
        assertThrows(CountersTransportException.class, () -> client.counter("c").value());
    }

    @Test
    void builderRequiresApiKey() {
        assertThrows(IllegalArgumentException.class, () -> CountersClient.builder().build());
        assertThrows(IllegalArgumentException.class, () -> CountersClient.builder().apiKey("").build());
    }
}
