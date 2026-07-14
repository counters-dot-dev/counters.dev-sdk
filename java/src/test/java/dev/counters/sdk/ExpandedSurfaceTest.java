package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Loopback coverage for the expanded Java SDK surface. */
class ExpandedSurfaceTest {

    private static final String UUID_V4 =
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    record Recorded(String method, String rawPath, String query, Map<String, String> headers, String body) {}

    private HttpServer server;
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String startServer(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        HttpHandler handler = exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : List.of("Authorization", "Idempotency-Key", "Content-Type")) {
                String v = exchange.getRequestHeaders().getFirst(name);
                if (v != null) headers.put(name, v);
            }
            Recorded r = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
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

    private CountersClient client(String baseUrl) {
        return CountersClient.builder().apiKey("secret").baseUrl(baseUrl).backoffMillis(1).build();
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
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

    private static void assertIdempotency(Recorded r) {
        String idem = r.headers().get("Idempotency-Key");
        assertNotNull(idem, "missing Idempotency-Key header");
        assertTrue(idem.matches(UUID_V4), "Idempotency-Key must be a v4 UUID, got: " + idem);
    }

    private static Instant at() {
        return Instant.parse("2026-01-01T00:00:00Z");
    }

    @Test
    void usageSendsAndParses() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"month\":\"2026-07\",\"ops\":{\"used\":12,\"quota\":null,\"resetsAt\":\"2026-08-01T00:00:00Z\"},"
                        + "\"counters\":{\"used\":3,\"max\":1000},"
                        + "\"limits\":{\"rateLimitRps\":50,\"maxCounters\":1000,\"monthlyOpsQuota\":null}}"));
        try (CountersClient c = client(baseUrl)) {
            Usage usage = c.usage();
            assertEquals("GET", recorded.get(0).method());
            assertEquals("/v1/usage", recorded.get(0).rawPath());
            assertEquals("2026-07", usage.month());
            assertEquals(12L, usage.operations().used());
            assertNull(usage.operations().quota());
            assertEquals(Instant.parse("2026-08-01T00:00:00Z"), usage.operations().resetsAt());
            assertEquals(3L, usage.counters().used());
            assertEquals(50L, usage.limits().rateLimitRequestsPerSecond());
            assertNull(usage.limits().monthlyOperationsQuota());
        }
    }

    @Test
    void derivedValueSurfacesNullWithReason() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"conversion\",\"value\":null,\"scale\":6,"
                        + "\"inputs\":{\"signups\":\"0\",\"visits\":\"0\"},\"reason\":\"division by zero\"}"));
        try (CountersClient c = client(baseUrl)) {
            DerivedHandle derived = c.derived("conversion");
            assertEquals("conversion", derived.key());
            DerivedValueResponse v = derived.value();
            assertEquals("/v1/derived/conversion/value", recorded.get(0).rawPath());
            assertEquals("conversion", v.key());
            assertNull(v.value(), "derived null value must surface as null, not an exception or zero");
            assertEquals("division by zero", v.reason());
            assertEquals("0", v.inputs().get("visits"));
        }
    }

    @Test
    void derivedSeriesSendsQueryAndParsesNullPoint() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"conversion:rate\",\"bucket\":\"1h\",\"tz\":\"Europe/London\",\"scale\":6,"
                        + "\"range\":{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-01T02:00:00Z\"},"
                        + "\"points\":[{\"t\":\"2026-01-01T00:00:00Z\",\"v\":\"0.010000\"},"
                        + "{\"t\":\"2026-01-01T01:00:00Z\",\"v\":null}]}"));
        try (CountersClient c = client(baseUrl)) {
            DerivedSeriesResponse s = c.derived("conversion:rate").series(
                    new DerivedSeriesParams(at(), at().plus(2, ChronoUnit.HOURS), "1h", "Europe/London"));
            assertEquals("/v1/derived/conversion%3Arate/series", recorded.get(0).rawPath());
            assertEquals(Map.of("from", "2026-01-01T00:00:00Z", "to", "2026-01-01T02:00:00Z",
                    "bucket", "1h", "tz", "Europe/London"), parseQuery(recorded.get(0).query()));
            assertEquals("Europe/London", s.timeZone());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.range().from());
            assertEquals(Instant.parse("2026-01-01T02:00:00Z"), s.range().to());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.points().get(0).timestamp());
            assertEquals("0.010000", s.points().get(0).value());
            assertEquals(Instant.parse("2026-01-01T01:00:00Z"), s.points().get(1).timestamp());
            assertNull(s.points().get(1).value(), "derived series null point must be preserved in place");
        }
    }

    @Test
    void leaderboardSendsParamsAndParsesBignumEntry() throws IOException {
        String huge = "170141183460469231731687303715884105728";
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"raid:1\",\"mode\":\"sum\",\"epoch\":3,\"order\":\"asc\",\"total\":\"" + huge + "\","
                        + "\"memberCount\":1,\"limit\":25,\"offset\":50,"
                        + "\"entries\":[{\"rank\":1,\"member\":\"bob\",\"value\":\"" + huge
                        + "\",\"updatedAt\":\"2026-01-01T00:00:02Z\"}]}"));
        try (CountersClient c = client(baseUrl)) {
            Leaderboard lb = c.counter("raid:1").leaderboard(new LeaderboardParams(25, 50, "asc", 3L));
            assertEquals("/v1/counters/raid%3A1/leaderboard", recorded.get(0).rawPath());
            assertEquals(Map.of("limit", "25", "offset", "50", "order", "asc", "epoch", "3"),
                    parseQuery(recorded.get(0).query()));
            assertEquals(huge, lb.total());
            assertEquals(huge, lb.entries().get(0).value(), "leaderboard values stay strings beyond u64");
            assertEquals(huge, new BigInteger(lb.entries().get(0).value()).toString());
            assertEquals(Instant.parse("2026-01-01T00:00:02Z"), lb.entries().get(0).updatedAt());
        }
    }

    @Test
    void leaderboardDefaultsOmitQuery() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"raid\",\"mode\":\"sum\",\"epoch\":0,\"order\":\"desc\","
                        + "\"memberCount\":0,\"limit\":100,\"offset\":0,\"entries\":[]}"));
        try (CountersClient c = client(baseUrl)) {
            Leaderboard lb = c.counter("raid").leaderboard();
            assertEquals("raid", lb.key());
            assertEquals(Map.of(), parseQuery(recorded.get(0).query()));
        }
    }

    @Test
    void windowLeaderboardSendsWindowAndParses() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"rocks\",\"mode\":\"sum\",\"window\":\"7d\",\"order\":\"desc\",\"total\":\"45\","
                        + "\"memberCount\":3,\"limit\":10,\"offset\":0,"
                        + "\"effectiveStart\":\"2025-12-25T00:00:00Z\","
                        + "\"effectiveEnd\":\"2026-01-01T00:00:00Z\","
                        + "\"entries\":[{\"rank\":1,\"member\":\"bob\",\"value\":\"25\"}]}"));
        try (CountersClient c = client(baseUrl)) {
            WindowLeaderboard lb = c.counter("rocks").windowLeaderboard(
                    new WindowLeaderboardParams("7d", 10, null, null, null));
            assertEquals(Map.of("limit", "10", "window", "7d"), parseQuery(recorded.get(0).query()));
            assertEquals("7d", lb.window());
            assertEquals(Instant.parse("2025-12-25T00:00:00Z"), lb.effectiveStart());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), lb.effectiveEnd());
            assertEquals("25", lb.entries().get(0).value());
        }
    }

    @Test
    void memberAddSendsMetadataOccurredAtAndBignum() throws IOException {
        String huge = "170141183460469231731687303715884105728";
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"board:1\",\"member\":\"user@host|a:b\",\"memberValue\":\"" + huge
                        + "\",\"memberAccepted\":true,\"mode\":\"sum\",\"epoch\":0,\"value\":\"" + huge + "\"}"));
        try (CountersClient c = client(baseUrl)) {
            MemberHandle member = c.counter("board:1").member("user@host|a:b");
            assertEquals("board:1", member.counterKey());
            assertEquals("user@host|a:b", member.member());
            MemberValue v = member.add(huge, new MemberWriteOptions("room1:500", at()));
            assertEquals("/v1/counters/board%3A1/members/user%40host%7Ca%3Ab/add", recorded.get(0).rawPath());
            assertIdempotency(recorded.get(0));
            assertEquals(Map.of("amount", huge, "metadata", "room1:500", "occurredAt", "2026-01-01T00:00:00Z"),
                    parseBody(recorded.get(0).body()));
            assertEquals(huge, v.memberValue());
        }
    }

    @Test
    void memberSubtractSendsAmountOnly() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"board\",\"member\":\"alice\",\"memberValue\":\"7\","
                        + "\"memberAccepted\":true,\"mode\":\"sum\",\"epoch\":0,\"value\":\"7\"}"));
        try (CountersClient c = client(baseUrl)) {
            c.counter("board").member("alice").subtract(BigInteger.valueOf(3));
            assertEquals("/v1/counters/board/members/alice/subtract", recorded.get(0).rawPath());
            assertIdempotency(recorded.get(0));
            assertEquals(Map.of("amount", "3"), parseBody(recorded.get(0).body()));
        }
    }

    @Test
    void memberSubmitSendsModeMetadataOccurredAt() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"scores\",\"member\":\"alice\",\"memberValue\":\"-1417\","
                        + "\"memberAccepted\":true,\"mode\":\"min\",\"epoch\":0}"));
        try (CountersClient c = client(baseUrl)) {
            MemberValue v = c.counter("scores").member("alice")
                    .submit("-1417", new SubmitOptions("min", "room1:400", at()));
            assertEquals("/v1/counters/scores/members/alice/submit", recorded.get(0).rawPath());
            assertIdempotency(recorded.get(0));
            assertEquals(Map.of("value", "-1417", "mode", "min", "metadata", "room1:400",
                    "occurredAt", "2026-01-01T00:00:00Z"), parseBody(recorded.get(0).body()));
            assertEquals("-1417", v.memberValue());
            assertNull(v.value());
        }
    }

    @Test
    void memberGetSendsQueryAndParses() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"snap\",\"member\":\"alice\",\"value\":\"10\",\"metadata\":\"room1:500\","
                        + "\"rank\":2,\"percentile\":\"83.33\",\"memberCount\":6,\"mode\":\"sum\","
                        + "\"epoch\":4,\"updatedAt\":\"2026-01-01T00:00:00Z\"}"));
        try (CountersClient c = client(baseUrl)) {
            MemberSnapshot snap = c.counter("snap").member("alice").get(new MemberGetParams(4L, "asc"));
            assertEquals("GET", recorded.get(0).method());
            assertEquals(Map.of("epoch", "4", "order", "asc"), parseQuery(recorded.get(0).query()));
            assertEquals("83.33", snap.percentile(), "percentile stays a scale-2 string");
            assertEquals("room1:500", snap.metadata());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), snap.updatedAt());
        }
    }

    @Test
    void memberRemoveDeletesWithIdempotency() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"rm\",\"member\":\"alice\",\"epoch\":0,\"value\":\"15\"}"));
        try (CountersClient c = client(baseUrl)) {
            MemberRemoved removed = c.counter("rm").member("alice").remove();
            assertEquals("DELETE", recorded.get(0).method());
            assertEquals("/v1/counters/rm/members/alice", recorded.get(0).rawPath());
            assertIdempotency(recorded.get(0));
            assertEquals("15", removed.value());
        }
    }

    @Test
    void memberWritesUseFreshIdempotencyKeys() throws IOException {
        String baseUrl = startServer((ex, r) -> {
            if ("DELETE".equals(r.method())) {
                json(ex, 200, "{\"key\":\"board\",\"member\":\"alice\",\"epoch\":0,\"value\":\"0\"}");
            } else {
                json(ex, 200,
                        "{\"key\":\"board\",\"member\":\"alice\",\"memberValue\":\"1\","
                                + "\"memberAccepted\":true,\"mode\":\"sum\",\"epoch\":0,\"value\":\"1\"}");
            }
        });
        try (CountersClient c = client(baseUrl)) {
            MemberHandle member = c.counter("board").member("alice");
            member.add(1);
            member.subtract(1);
            member.submit(1, new SubmitOptions("sum"));
            member.remove();

            List<String> keys = new ArrayList<>();
            for (Recorded r : recorded) {
                assertIdempotency(r);
                keys.add(r.headers().get("Idempotency-Key"));
            }
            assertEquals(4, keys.stream().distinct().count(), "every member write uses a fresh idempotency key");
        }
    }

    @Test
    void memberSeriesSendsMemberQueryAndParses() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"counterKey\":\"board\",\"member\":\"alice|bob\",\"bucket\":\"1h\",\"mode\":\"delta\","
                        + "\"range\":{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-01T01:00:00Z\"},"
                        + "\"points\":[{\"t\":\"2026-01-01T00:00:00Z\",\"v\":\"5\"}]}"));
        try (CountersClient c = client(baseUrl)) {
            MemberSeriesResponse s = c.counter("board").memberSeries("alice|bob",
                    new SeriesParams(at(), at().plus(1, ChronoUnit.HOURS), "1h", "delta", null, null));
            assertEquals("alice|bob", parseQuery(recorded.get(0).query()).get("member"));
            assertEquals("delta", parseQuery(recorded.get(0).query()).get("mode"));
            assertNull(s.timeZone());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.points().get(0).timestamp());
            assertEquals("5", s.points().get(0).value());
        }
    }

    @Test
    void groupSeriesSendsGroupByAndParses() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"counterKey\":\"board\",\"bucket\":\"1h\",\"mode\":\"delta\","
                        + "\"range\":{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-01T01:00:00Z\"},"
                        + "\"series\":[{\"member\":\"alice\",\"points\":[{\"t\":\"2026-01-01T00:00:00Z\",\"v\":\"5\"}]}]}"));
        try (CountersClient c = client(baseUrl)) {
            MemberGroupSeriesResponse s = c.counter("board").groupSeries(
                    new SeriesParams(at(), at().plus(1, ChronoUnit.HOURS), "1h"));
            assertEquals("member", parseQuery(recorded.get(0).query()).get("groupBy"));
            assertEquals("delta", s.mode());
            assertEquals("alice", s.series().get(0).member());
            assertNull(s.timeZone());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
                    s.series().get(0).points().get(0).timestamp());
            assertEquals("5", s.series().get(0).points().get(0).value());
        }
    }

    @Test
    void windowLeaderboardOnScoreBoardHasBoardModeAndNoTotal() throws IOException {
        // A windowed board follows the board mode; score boards omit `total` entirely on the wire.
        String baseUrl = startServer((ex, r) -> json(ex, 200,
                "{\"key\":\"best-lap\",\"mode\":\"min\",\"window\":\"7d\",\"order\":\"asc\","
                        + "\"memberCount\":2,\"limit\":100,\"offset\":0,"
                        + "\"effectiveStart\":\"2026-06-27T00:00:00Z\","
                        + "\"effectiveEnd\":\"2026-07-04T09:30:00Z\","
                        + "\"entries\":[{\"rank\":1,\"member\":\"alice\",\"value\":\"1417\"}]}"));
        try (CountersClient c = client(baseUrl)) {
            WindowLeaderboard lb = c.counter("best-lap")
                    .windowLeaderboard(new WindowLeaderboardParams("7d"));
            assertEquals("min", lb.mode());
            assertNull(lb.total(), "total must be null on score-board windows");
            assertEquals("1417", lb.entries().get(0).value());
        }
    }

    @Test
    void localValidationRejectsMemberKeyMetadataAndWindowBeforeRequest() throws IOException {
        String baseUrl = startServer((ex, r) -> json(ex, 500, "{}"));
        try (CountersClient c = client(baseUrl)) {
            assertThrows(CountersValidationException.class, () -> c.counter("board").member("bad member"));
            assertThrows(CountersValidationException.class,
                    () -> new MemberWriteOptions("€".repeat(342)));
            assertThrows(CountersValidationException.class,
                    () -> c.counter("board").windowLeaderboard(new WindowLeaderboardParams("2h")));
            assertTrue(recorded.isEmpty(), "validation failures must not issue requests");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void leaderboardConformanceVectors() throws IOException {
        Map<String, Object> vectors = ConformanceTest.loadVectors("leaderboard/cases.json");

        for (Object raw : (List<?>) vectors.get("encodeQuery")) {
            Map<String, Object> c = (Map<String, Object>) raw;
            Map<String, Object> params = (Map<String, Object>) c.get("params");
            if (c.containsKey("expect")) {
                assertThrows(CountersValidationException.class,
                        () -> new WindowLeaderboardParams((String) params.get("window")), (String) c.get("name"));
                continue;
            }
            recorded.clear();
            boolean windowed = params.containsKey("window");
            String response = windowed
                    ? "{\"key\":\"board\",\"mode\":\"sum\",\"window\":\"" + params.get("window")
                            + "\",\"order\":\"desc\",\"total\":\"0\",\"memberCount\":0,\"limit\":100,\"offset\":0,"
                            + "\"effectiveStart\":\"2026-01-01T00:00:00Z\","
                            + "\"effectiveEnd\":\"2026-01-01T01:00:00Z\",\"entries\":[]}"
                    : "{\"key\":\"board\",\"mode\":\"sum\",\"epoch\":0,\"order\":\"desc\","
                            + "\"memberCount\":0,\"limit\":100,\"offset\":0,\"entries\":[]}";
            String baseUrl = startServer((ex, r) -> json(ex, 200, response));
            try (CountersClient client = client(baseUrl)) {
                if (windowed) {
                    client.counter("board").windowLeaderboard(new WindowLeaderboardParams(
                            (String) params.get("window"),
                            intParam(params, "limit"),
                            intParam(params, "offset"),
                            (String) params.get("order"),
                            longParam(params, "epoch")));
                } else {
                    client.counter("board").leaderboard(new LeaderboardParams(
                            intParam(params, "limit"),
                            intParam(params, "offset"),
                            (String) params.get("order"),
                            longParam(params, "epoch")));
                }
                assertQueryExact((Map<String, Object>) c.get("query"), recorded.get(0).query(), (String) c.get("name"));
            }
            stopServer();
        }

        for (Object raw : (List<?>) vectors.get("encodeBody")) {
            Map<String, Object> c = (Map<String, Object>) raw;
            Map<String, Object> input = (Map<String, Object>) c.get("input");
            Map<String, Object> want = (Map<String, Object>) c.get("body");
            recorded.clear();
            String baseUrl = startServer((ex, r) -> json(ex, 200,
                    "{\"key\":\"board\",\"member\":\"alice\",\"memberValue\":\"1\","
                            + "\"memberAccepted\":true,\"mode\":\"sum\",\"epoch\":0,\"value\":\"1\"}"));
            try (CountersClient client = client(baseUrl)) {
                MemberHandle member = client.counter("board").member("alice");
                switch ((String) c.get("op")) {
                    case "memberAdd" -> member.add((String) input.get("amount"),
                            new MemberWriteOptions((String) input.get("metadata"),
                                    input.get("occurredAt") == null ? null
                                            : Instant.parse((String) input.get("occurredAt"))));
                    case "memberSubtract" -> member.subtract((String) input.get("amount"));
                    case "memberSubmit" -> member.submit((String) input.get("value"),
                            new SubmitOptions((String) input.get("mode"), (String) input.get("metadata"),
                                    input.get("occurredAt") == null ? null
                                            : Instant.parse((String) input.get("occurredAt"))));
                    default -> throw new AssertionError("unknown op " + c.get("op"));
                }
                assertEquals(want, parseBody(recorded.get(0).body()), (String) c.get("name"));
                assertIdempotency(recorded.get(0));
            }
            stopServer();
        }

        for (Object raw : (List<?>) vectors.get("parse")) {
            Map<String, Object> c = (Map<String, Object>) raw;
            String kind = (String) c.get("kind");
            Map<String, Object> body = (Map<String, Object>) c.get("body");
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            recorded.clear();
            String baseUrl = startServer((ex, r) -> json(ex, 200, Json.write(body)));
            try (CountersClient client = client(baseUrl)) {
                Object got = switch (kind) {
                    case "leaderboard" -> client.counter((String) body.get("key")).leaderboard();
                    case "windowLeaderboard" -> client.counter((String) body.get("key")).windowLeaderboard(
                            new WindowLeaderboardParams((String) body.get("window")));
                    case "memberValue" -> client.counter((String) body.get("key"))
                            .member((String) body.get("member")).add(1);
                    case "memberSnapshot" -> client.counter((String) body.get("key"))
                            .member((String) body.get("member")).get();
                    case "memberRemoved" -> client.counter((String) body.get("key"))
                            .member((String) body.get("member")).remove();
                    default -> throw new AssertionError("unknown kind " + kind);
                };
                assertLeaderboardParse(kind, expect, got, (String) c.get("name"));
            }
            stopServer();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void derivedConformanceVectors() throws IOException {
        Map<String, Object> vectors = ConformanceTest.loadVectors("derived/cases.json");
        for (Object raw : (List<?>) vectors.get("encodeQuery")) {
            Map<String, Object> c = (Map<String, Object>) raw;
            Map<String, Object> p = (Map<String, Object>) c.get("params");
            recorded.clear();
            String baseUrl = startServer((ex, r) -> json(ex, 200,
                    "{\"key\":\"conversion\",\"bucket\":\"" + p.get("bucket") + "\",\"scale\":6,"
                            + "\"range\":{\"from\":\"" + p.get("from") + "\",\"to\":\"" + p.get("to")
                            + "\"},\"points\":[]}"));
            try (CountersClient client = client(baseUrl)) {
                client.derived("conversion").series(new DerivedSeriesParams(
                        Instant.parse((String) p.get("from")),
                        Instant.parse((String) p.get("to")),
                        (String) p.get("bucket"),
                        (String) p.get("tz")));
                assertQueryExact((Map<String, Object>) c.get("query"), recorded.get(0).query(), (String) c.get("name"));
                for (Object absent : (List<?>) c.get("absent")) {
                    assertFalse(parseQuery(recorded.get(0).query()).containsKey((String) absent), (String) absent);
                }
            }
            stopServer();
        }

        for (Object raw : (List<?>) vectors.get("parse")) {
            Map<String, Object> c = (Map<String, Object>) raw;
            Map<String, Object> body = (Map<String, Object>) c.get("body");
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            recorded.clear();
            String baseUrl = startServer((ex, r) -> json(ex, 200, Json.write(body)));
            try (CountersClient client = client(baseUrl)) {
                if ("derivedValue".equals(c.get("kind"))) {
                    DerivedValueResponse v = client.derived((String) body.get("key")).value();
                    assertEquals(expect.get("key"), v.key(), (String) c.get("name"));
                    assertEquals(expect.get("value"), v.value(), (String) c.get("name"));
                    assertEquals(((Number) expect.get("scale")).longValue(), v.scale(), (String) c.get("name"));
                    assertEquals(expect.get("inputs"), v.inputs(), (String) c.get("name"));
                    if (Boolean.TRUE.equals(expect.get("reasonAbsent"))) assertNull(v.reason(), (String) c.get("name"));
                    if (expect.containsKey("reason")) assertEquals(expect.get("reason"), v.reason(), (String) c.get("name"));
                } else {
                    Map<String, Object> range = (Map<String, Object>) body.get("range");
                    DerivedSeriesResponse s = client.derived((String) body.get("key")).series(
                            new DerivedSeriesParams(
                                    Instant.parse((String) range.get("from")),
                                    Instant.parse((String) range.get("to")),
                                    (String) body.get("bucket")));
                    assertEquals(expect.get("key"), s.key(), (String) c.get("name"));
                    assertEquals(expect.get("bucket"), s.bucket(), (String) c.get("name"));
                    assertEquals(((Number) expect.get("scale")).longValue(), s.scale(), (String) c.get("name"));
                    List<?> points = (List<?>) expect.get("points");
                    assertEquals(points.size(), s.points().size(), (String) c.get("name"));
                    for (int i = 0; i < points.size(); i++) {
                        Map<String, Object> ep = (Map<String, Object>) points.get(i);
                        assertEquals(Instant.parse((String) ep.get("t")),
                                s.points().get(i).timestamp(), (String) c.get("name"));
                        assertEquals(ep.get("v"), s.points().get(i).value(), (String) c.get("name"));
                    }
                }
            }
            stopServer();
        }
    }

    private static Integer intParam(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.intValue() : null;
    }

    private static Long longParam(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : null;
    }

    private static void assertQueryExact(Map<String, Object> expected, String rawQuery, String name) {
        Map<String, String> got = parseQuery(rawQuery);
        assertEquals(expected.keySet(), got.keySet(), "query keys for " + name);
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            assertEquals(e.getValue(), got.get(e.getKey()), name + ": " + e.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertLeaderboardParse(String kind, Map<String, Object> expect, Object got, String name) {
        switch (kind) {
            case "leaderboard" -> {
                Leaderboard lb = (Leaderboard) got;
                assertEquals(expect.get("key"), lb.key(), name);
                assertEquals(expect.get("mode"), lb.mode(), name);
                assertEquals(((Number) expect.get("epoch")).longValue(), lb.epoch(), name);
                assertEquals(expect.get("order"), lb.order(), name);
                if (expect.containsKey("total")) assertEquals(expect.get("total"), lb.total(), name);
                if (Boolean.TRUE.equals(expect.get("totalAbsent"))) assertNull(lb.total(), name);
                assertEquals(((Number) expect.get("memberCount")).longValue(), lb.memberCount(), name);
                List<?> entries = (List<?>) expect.get("entries");
                assertEquals(entries.size(), lb.entries().size(), name);
                for (int i = 0; i < entries.size(); i++) {
                    Map<String, Object> e = (Map<String, Object>) entries.get(i);
                    LeaderboardEntry ge = lb.entries().get(i);
                    assertEquals(((Number) e.get("rank")).longValue(), ge.rank(), name);
                    assertEquals(e.get("member"), ge.member(), name);
                    assertEquals(e.get("value"), ge.value(), name);
                    if (e.containsKey("metadata")) assertEquals(e.get("metadata"), ge.metadata(), name);
                }
            }
            case "windowLeaderboard" -> {
                WindowLeaderboard lb = (WindowLeaderboard) got;
                assertEquals(expect.get("key"), lb.key(), name);
                assertEquals(expect.get("mode"), lb.mode(), name);
                assertEquals(expect.get("window"), lb.window(), name);
                assertEquals(expect.get("total"), lb.total(), name);
                assertEquals(Instant.parse((String) expect.get("effectiveStart")), lb.effectiveStart(), name);
                assertEquals(Instant.parse((String) expect.get("effectiveEnd")), lb.effectiveEnd(), name);
                assertEquals(((List<?>) expect.get("entries")).size(), lb.entries().size(), name);
            }
            case "memberValue" -> {
                MemberValue v = (MemberValue) got;
                assertEquals(expect.get("key"), v.key(), name);
                assertEquals(expect.get("member"), v.member(), name);
                assertEquals(expect.get("memberValue"), v.memberValue(), name);
                assertEquals(expect.get("memberAccepted"), v.memberAccepted(), name);
                assertEquals(expect.get("mode"), v.mode(), name);
                assertEquals(((Number) expect.get("epoch")).longValue(), v.epoch(), name);
                if (expect.containsKey("value")) assertEquals(expect.get("value"), v.value(), name);
                if (Boolean.TRUE.equals(expect.get("valueAbsent"))) assertNull(v.value(), name);
            }
            case "memberSnapshot" -> {
                MemberSnapshot v = (MemberSnapshot) got;
                assertEquals(expect.get("key"), v.key(), name);
                assertEquals(expect.get("member"), v.member(), name);
                assertEquals(expect.get("value"), v.value(), name);
                assertEquals(expect.get("metadata"), v.metadata(), name);
                assertEquals(((Number) expect.get("rank")).longValue(), v.rank(), name);
                assertEquals(expect.get("percentile"), v.percentile(), name);
                assertEquals(((Number) expect.get("memberCount")).longValue(), v.memberCount(), name);
                assertEquals(expect.get("mode"), v.mode(), name);
                assertEquals(((Number) expect.get("epoch")).longValue(), v.epoch(), name);
            }
            case "memberRemoved" -> {
                MemberRemoved v = (MemberRemoved) got;
                assertEquals(expect.get("key"), v.key(), name);
                assertEquals(expect.get("member"), v.member(), name);
                assertEquals(((Number) expect.get("epoch")).longValue(), v.epoch(), name);
                assertEquals(expect.get("value"), v.value(), name);
            }
            default -> throw new AssertionError("unknown kind " + kind);
        }
    }
}
