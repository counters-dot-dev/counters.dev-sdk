package dev.counters.examples;

// counters.dev Java SDK — example app + end-to-end suite.
//
// This program is both living documentation and the E2E gate: it drives EVERY public method of the
// SDK against a real running server, asserts the outcomes, then replays the shared
// conformance/http vectors through the client. If a public method is not demonstrated here, the
// run fails — "if it isn't demonstrated, it isn't shipped."
//
// Mirrors typescript/examples/e2e/main.mjs section for section, adapted to Java (builder
// instead of options object, overloads for long|String|BigInteger amounts, reflection instead of
// prototype walking for the surface gate).
//
// Env (see .github/actions/e2e-server): COUNTERS_BASE_URL (origin, no /v1), COUNTERS_API_KEY_A,
// COUNTERS_API_KEY_B, COUNTERS_PK_TOKEN (read-only token scoped to the fixed key "pk-demo").

import dev.counters.sdk.Counter;
import dev.counters.sdk.CounterHandle;
import dev.counters.sdk.CounterPage;
import dev.counters.sdk.CountersApiException;
import dev.counters.sdk.CountersClient;
import dev.counters.sdk.DerivedHandle;
import dev.counters.sdk.DerivedSeriesParams;
import dev.counters.sdk.Leaderboard;
import dev.counters.sdk.LeaderboardEntry;
import dev.counters.sdk.LeaderboardParams;
import dev.counters.sdk.MemberGetParams;
import dev.counters.sdk.MemberHandle;
import dev.counters.sdk.MemberRemoved;
import dev.counters.sdk.MemberSnapshot;
import dev.counters.sdk.MemberValue;
import dev.counters.sdk.MemberWriteOptions;
import dev.counters.sdk.ReadOnlyCountersClient;
import dev.counters.sdk.SeriesParams;
import dev.counters.sdk.SeriesPoint;
import dev.counters.sdk.SeriesResponse;
import dev.counters.sdk.SubmitOptions;
import dev.counters.sdk.Usage;
import dev.counters.sdk.ValueResponse;
import dev.counters.sdk.WindowLeaderboardParams;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class E2e {

    private static String baseUrl;
    private static String keyA;
    private static String keyB;
    private static String pkToken;

    /** Run-unique namespace: fresh counters, stable epochs. */
    private static final String NS = "e2e-java-" + Long.toString(System.currentTimeMillis(), 36) + "-";
    /** Captured once per run, UTC, truncated to seconds; all relative times resolve against it. */
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static final Set<String> INVOKED = new HashSet<>();
    private static int checks = 0;

    public static void main(String[] args) {
        String origin = required("COUNTERS_BASE_URL").replaceAll("/$", "");
        keyA = required("COUNTERS_API_KEY_A");
        keyB = required("COUNTERS_API_KEY_B");
        pkToken = required("COUNTERS_PK_TOKEN");
        baseUrl = origin + "/v1";

        try {
            System.out.println("counters.dev Java SDK e2e — " + baseUrl + " (ns " + NS + ")");
            tour();
            System.out.println("  ok   full public-surface tour");
            leaderboards();
            System.out.println("  ok   leaderboards + members lifecycle");
            derived();
            System.out.println("  ok   derived-counter read wiring");
            replayVectors();
            surfaceGate();
            System.out.println("\nPASS — entire public SDK surface + shared vectors verified against a live server ("
                    + checks + " assertions)");
        } catch (Throwable e) {
            System.err.println("\nFAIL — " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            System.exit(1);
        }
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            System.err.println("missing required env: " + name);
            System.exit(2);
        }
        return v;
    }

    private static void check(boolean cond, String what) {
        checks++;
        if (!cond) throw new AssertionError("assertion failed: " + what);
    }

    private static void checkEq(Object actual, Object expected, String what) {
        check(Objects.equals(actual, expected), what + ": expected " + expected + ", got " + actual);
    }

    private static void expectStatus(Runnable call, int status, String what) {
        try {
            call.run();
        } catch (CountersApiException e) {
            if (e.status() == status) return;
            throw new AssertionError(what + ": expected CountersApiException(" + status + "), got " + e);
        } catch (RuntimeException e) {
            throw new AssertionError(what + ": expected CountersApiException(" + status + "), got " + e);
        }
        throw new AssertionError(what + ": expected CountersApiException(" + status + "), but the call succeeded");
    }

    private static Instant minutes(long n) {
        return T0.plus(n, ChronoUnit.MINUTES);
    }

    // ── 1. The grand tour: every public method, the way an integrator would use it ──────────────

    private static void tour() {
        Instant from = T0.minus(24, ChronoUnit.HOURS);
        Instant to = T0.plus(24, ChronoUnit.HOURS);

        // Every builder knob in one place; try-with-resources flushes buffered writes on the way out.
        try (CountersClient client = CountersClient.builder()
                .apiKey(keyA)
                .baseUrl(baseUrl)
                .httpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build())
                .maxRetries(3)
                .backoffMillis(200)
                .batchEnabled(true)
                .maxBatchSize(100)
                .batchIntervalMillis(200)
                .onBatchError(e -> System.err.println("batch flush failed: " + e))
                .build()) {
            INVOKED.add("CountersClient.builder().build()");

            // A typed handle per counter. Keys are validated client-side.
            CounterHandle signups = client.counter(NS + "signups");
            INVOKED.add("CountersClient.counter");
            checkEq(signups.key(), NS + "signups", "handle exposes its validated key");
            INVOKED.add("CounterHandle.key");

            // Confirmed writes: apply immediately, return the new state.
            Counter first = signups.addNow(5);
            INVOKED.add("CounterHandle.addNow");
            checkEq(first.value(), "5", "addNow(5) on a fresh counter");
            checkEq(first.epoch(), 0L, "fresh counter epoch");

            Counter afterSub = signups.subtractNow("2");
            INVOKED.add("CounterHandle.subtractNow");
            checkEq(afterSub.value(), "3", "subtractNow(2)");

            // Fire-and-forget writes: buffered, coalesced per counter, flushed in the background.
            signups.add(BigInteger.valueOf(4));
            INVOKED.add("CounterHandle.add");
            signups.subtract(1);
            INVOKED.add("CounterHandle.subtract");
            client.flush();
            INVOKED.add("CountersClient.flush");

            ValueResponse current = signups.value();
            INVOKED.add("CounterHandle.value");
            checkEq(current.value(), "6", "value after confirmed + buffered writes (5-2+4-1)");

            // Event-time writes: occurredAt buckets the op into the past; totals are unaffected.
            signups.addNow(10, T0.minus(2, ChronoUnit.HOURS));
            checkEq(signups.value().value(), "16", "total after an event-time write");

            // Series at every granularity the plan allows (pro: down to 1m). Sum == total delta.
            for (String bucket : List.of("1m", "5m", "1h", "1d", "1w", "1mo")) {
                SeriesResponse series = signups.series(new SeriesParams(from, to, bucket));
                BigInteger sum = BigInteger.ZERO;
                for (SeriesPoint p : series.points()) {
                    check(p.timestamp() != null, "series point exposes its bucket timestamp as an Instant");
                    sum = sum.add(new BigInteger(p.value()));
                }
                checkEq(sum.toString(), "16", "series(" + bucket + ") sums to the total delta");
            }
            INVOKED.add("CounterHandle.series");

            // Arbitrary precision is the headline guarantee: 10^32 round-trips exactly.
            CounterHandle big = client.counter(NS + "big");
            String huge = "100000000000000000000000000000000";
            big.addNow(huge);
            big.addNow(BigInteger.ONE);
            checkEq(big.value().value(), "100000000000000000000000000000001", "10^32 + 1 round trip");
            big.subtractNow(new BigInteger(huge));
            checkEq(big.value().value(), "1", "subtracting 10^32 back down");

            // list: pages in key order; follow nextCursor. (Also the no-arg first-page overload.)
            for (String suffix : List.of("pg-a", "pg-b", "pg-c")) client.counter(NS + suffix).addNow(1);
            List<String> seen = new ArrayList<>();
            String cursor = null;
            do {
                CounterPage page = client.list(cursor, 2);
                for (Counter c : page.data()) seen.add(c.key());
                cursor = page.nextCursor();
            } while (cursor != null);
            INVOKED.add("CountersClient.list");
            check(!client.list().data().isEmpty(), "list() default first page is non-empty");
            List<String> wantOrder = List.of(NS + "pg-a", NS + "pg-b", NS + "pg-c");
            int matched = 0;
            for (String k : seen) if (matched < wantOrder.size() && k.equals(wantOrder.get(matched))) matched++;
            checkEq(matched, wantOrder.size(), "list pagination walks all counters in key order");

            // clear: value back to 0 in a new epoch; history is retained.
            Counter cleared = signups.clear();
            INVOKED.add("CounterHandle.clear");
            checkEq(cleared.value(), "0", "clear resets to zero");
            checkEq(cleared.epoch(), 1L, "clear bumps the epoch");

            // delete: tombstoned; further use is a 404.
            CounterHandle doomed = client.counter(NS + "doomed");
            doomed.addNow(1);
            doomed.delete();
            INVOKED.add("CounterHandle.delete");
            expectStatus(() -> doomed.value(), 404, "value after delete");
            expectStatus(() -> doomed.addNow(1), 404, "write after delete");

            // Tenant isolation: org B's key cannot see org A's counters.
            try (CountersClient clientB = CountersClient.builder().apiKey(keyB).baseUrl(baseUrl).build()) {
                expectStatus(() -> clientB.counter(NS + "signups").value(), 404, "cross-tenant read");
            }

            // Publishable tokens: read-only, scoped. The pk_ token is just the bearer key.
            CounterHandle pkDemo = client.counter("pk-demo"); // fixed key the token is scoped to
            pkDemo.addNow(1); // ensure it exists before clearing (first run on a fresh DB)
            pkDemo.clear();
            pkDemo.addNow(7);
            // publishableBuilder() returns a read-only static type: writes, list, usage, and derived
            // operations are absent rather than calls that fail later with 403.
            try (ReadOnlyCountersClient pkClient = CountersClient.publishableBuilder()
                    .apiKey(pkToken)
                    .baseUrl(baseUrl)
                    .build()) {
                INVOKED.add("CountersClient.publishableBuilder");
                checkEq(pkClient.counter("pk-demo").value().value(), "7", "pk token reads its scoped counter");
                pkClient.counter("pk-demo").series(new SeriesParams(from, to, "1h")); // read surface also includes series
                expectStatus(() -> pkClient.counter(NS + "signups").value(), 403, "pk token cannot leave its scope");
            }

            // Usage: org-wide quota state. Assertions are lower-bound/tolerant because the org is shared
            // across cases and the endpoint reports the whole current month.
            Usage usage = client.usage();
            INVOKED.add("CountersClient.usage");
            check(usage.operations().used() >= 1, "usage reports at least the writes this run made");
            check(usage.counters().used() >= 1, "usage reports at least one live counter");
            check(usage.operations().resetsAt() != null, "usage carries a resetsAt instant");
            check(usage.month() != null && !usage.month().isEmpty(), "usage carries the UTC month");
        } // try-with-resources: AutoCloseable close() flushes buffered writes and stops the timer
        INVOKED.add("CountersClient.close");
    }

    // ── 1b. Leaderboards & members: the full board lifecycle against a live server ──────────────

    private static void leaderboards() {
        Instant from = T0.minus(24, ChronoUnit.HOURS);
        Instant to = T0.plus(24, ChronoUnit.HOURS);

        try (CountersClient client = CountersClient.builder().apiKey(keyA).baseUrl(baseUrl).build()) {
            CounterHandle board = client.counter(NS + "lb");
            MemberHandle alice = board.member("alice");
            INVOKED.add("CounterHandle.member");
            INVOKED.add("MemberHandle.counterKey");
            INVOKED.add("MemberHandle.member");
            checkEq(alice.counterKey(), NS + "lb", "member handle exposes counter key");
            checkEq(alice.member(), "alice", "member handle exposes member key");

            MemberHandle bob = board.member("bob");
            MemberHandle carol = board.member("carol");

            MemberValue a1 = alice.add(10);
            INVOKED.add("MemberHandle.add");
            checkEq(a1.memberValue(), "10", "alice member add");
            check(a1.memberAccepted(), "sum add is always accepted");
            checkEq(a1.mode(), "sum", "first member add configures the board as sum");
            checkEq(a1.value(), "10", "board total after alice");
            bob.add(25);
            MemberValue c1 = carol.add(10);
            checkEq(c1.value(), "45", "board total after three members (10+25+10)");

            Leaderboard lb = board.leaderboard();
            INVOKED.add("CounterHandle.leaderboard");
            checkEq(lb.mode(), "sum", "leaderboard mode is sum");
            checkEq(lb.order(), "desc", "a sum board ranks highest-first by default");
            checkEq(lb.total(), "45", "leaderboard total");
            check(lb.total() instanceof String, "leaderboard total is a string");
            checkEq(lb.memberCount(), 3L, "member count");
            checkEq(lb.entries().get(0).member(), "bob", "rank 1 is bob");
            checkEq(lb.entries().get(0).rank(), 1L, "bob is rank 1");
            checkEq(lb.entries().get(0).value(), "25", "bob value");
            Instant leaderboardUpdatedAt = lb.entries().get(0).updatedAt();
            check(leaderboardUpdatedAt != null, "leaderboard entry carries its required updatedAt instant");
            checkEq(lb.entries().get(1).rank(), 2L, "alice/carol tie at rank 2");
            checkEq(lb.entries().get(2).rank(), 2L, "the tie shares rank 2");

            MemberValue aSub = alice.subtract(5);
            INVOKED.add("MemberHandle.subtract");
            checkEq(aSub.memberValue(), "5", "alice value after subtracting 5");
            checkEq(aSub.value(), "40", "board total after the subtract");

            MemberSnapshot snap = bob.get();
            INVOKED.add("MemberHandle.get");
            checkEq(snap.rank(), 1L, "bob snapshot rank");
            checkEq(snap.value(), "25", "bob snapshot value");
            checkEq(snap.percentile(), "100.00", "the leader's percentile is 100.00");
            check(snap.percentile() instanceof String, "percentile stays a string");
            Instant snapshotUpdatedAt = snap.updatedAt();
            check(snapshotUpdatedAt != null, "member snapshot carries its required updatedAt instant");

            checkEq(carol.remove().value(), "30", "board total after removing carol (40 - 10)");
            INVOKED.add("MemberHandle.remove");
            checkEq(board.leaderboard().memberCount(), 2L, "member count after removal");

            // Member series / window reads require a dashboard-plane toggle. These API-key-only runs
            // assert the typed 400 and still demonstrate the Java split methods.
            expectStatus(() -> board.memberSeries("alice", new SeriesParams(from, to, "1h")),
                    400, "member series without member series enabled");
            INVOKED.add("CounterHandle.memberSeries");
            expectStatus(() -> board.groupSeries(new SeriesParams(from, to, "1h")),
                    400, "group member series without member series enabled");
            INVOKED.add("CounterHandle.groupSeries");
            expectStatus(() -> board.windowLeaderboard(new WindowLeaderboardParams("7d")),
                    400, "windowed leaderboard without member series enabled");
            INVOKED.add("CounterHandle.windowLeaderboard");

            CounterHandle raid = client.counter(NS + "raid");
            MemberHandle team = raid.member("alice|bob|carol");
            MemberValue s1 = team.submit(1502, new SubmitOptions("min", "room1:500"));
            INVOKED.add("MemberHandle.submit");
            checkEq(s1.memberValue(), "1502", "first min submit stands");
            check(s1.memberAccepted(), "first submit accepted");
            checkEq(s1.mode(), "min", "first submit configures the board as min");
            MemberValue s2 = team.submit(1417, new SubmitOptions("min", "room1:400"));
            checkEq(s2.memberValue(), "1417", "a better lower min is kept");
            check(s2.memberAccepted(), "the improving submit is accepted");
            MemberValue s3 = team.submit(1600, new SubmitOptions("min"));
            checkEq(s3.memberValue(), "1417", "a worse submit keeps the standing best");
            check(!s3.memberAccepted(), "the worse submit is recorded but not accepted");

            MemberSnapshot teamSnap = team.get();
            checkEq(teamSnap.value(), "1417", "kept-best value in the snapshot");
            checkEq(teamSnap.metadata(), "room1:400", "metadata rode the accepted submit");

            raid.member("dan").submit(1300, new SubmitOptions("min"));
            Leaderboard raidLb = raid.leaderboard();
            checkEq(raidLb.mode(), "min", "raid board mode is min");
            checkEq(raidLb.order(), "asc", "a min board ranks lowest-first by default");
            checkEq(raidLb.entries().get(0).member(), "dan", "dan (1300) is the best min");
            checkEq(raidLb.entries().get(0).value(), "1300", "dan value");
            checkEq(raidLb.entries().get(1).member(), "alice|bob|carol", "the team is rank 2");
            checkEq(raidLb.entries().get(1).metadata(), "room1:400", "entry carries accepted metadata");
        }
    }

    // ── 1c. Derived counters: read wiring + error mapping (definitions are dashboard-only) ──────

    private static void derived() {
        try (CountersClient client = CountersClient.builder().apiKey(keyA).baseUrl(baseUrl).build()) {
            DerivedHandle d = client.derived(NS + "conversion");
            INVOKED.add("CountersClient.derived");
            INVOKED.add("DerivedHandle.key");
            checkEq(d.key(), NS + "conversion", "derived handle exposes its validated key");
            expectStatus(() -> d.value(), 404, "derived value with no definition");
            INVOKED.add("DerivedHandle.value");
            expectStatus(() -> d.series(new DerivedSeriesParams(T0.minus(1, ChronoUnit.HOURS), T0, "1h")),
                    404, "derived series with no definition");
            INVOKED.add("DerivedHandle.series");
        }
    }

    // ── 2. Shared conformance vectors, replayed through the real client ─────────────────────────

    private static Path findCasesFile() {
        // examples/e2e -> java -> repo root; walking up also works from any repo subdir.
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("conformance").resolve("http").resolve("cases.json");
            if (Files.exists(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "cannot locate conformance/http/cases.json above " + Paths.get("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object o) {
        return (List<Object>) o;
    }

    private static void replayVectors() throws Exception {
        Map<String, Object> doc = obj(JsonMini.parse(Files.readString(findCasesFile())));
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object c : arr(doc.get("cases"))) {
            if ("all".equals(obj(c).get("scope"))) cases.add(obj(c)); // scope:http needs raw-HTTP capabilities SDKs do not expose
        }
        check(cases.size() >= 10, "expected a healthy scope:all vector count, got " + cases.size());

        try (CountersClient a = CountersClient.builder().apiKey(keyA).baseUrl(baseUrl).build();
             CountersClient b = CountersClient.builder().apiKey(keyB).baseUrl(baseUrl).build()) {
            Map<String, CountersClient> clients = Map.of("A", a, "B", b);

            for (int i = 0; i < cases.size(); i++) {
                Map<String, Object> c = cases.get(i);
                String prefix = NS + "c" + i + "-";
                List<Object> steps = arr(c.get("steps"));
                for (int s = 0; s < steps.size(); s++) {
                    Map<String, Object> step = obj(steps.get(s));
                    Map<String, Object> op = obj(step.get("do"));
                    Map<String, Object> expect = obj(step.get("expect"));
                    CountersClient client = clients.get((String) op.get("org"));
                    CounterHandle handle = op.get("key") != null ? client.counter(prefix + op.get("key")) : null;
                    Instant occurredAt = op.get("occurredAtMin") != null
                            ? minutes(((Number) op.get("occurredAtMin")).longValue())
                            : null;
                    String where = c.get("name") + " step " + s;

                    long status = ((Number) expect.get("status")).longValue();
                    if (status < 200 || status >= 300) {
                        expectStatus(() -> runOp(client, handle, op, occurredAt), (int) status, where);
                        continue;
                    }
                    Object body;
                    try {
                        body = runOp(client, handle, op, occurredAt);
                    } catch (RuntimeException e) {
                        throw new AssertionError(where + ": expected success, got " + e);
                    }
                    if (expect.containsKey("key")) checkEq(keyOf(body), prefix + expect.get("key"), where + ": key");
                    if (expect.containsKey("value")) checkEq(valueOf(body), expect.get("value"), where + ": value");
                    if (expect.containsKey("epoch")) {
                        checkEq(epochOf(body), ((Number) expect.get("epoch")).longValue(), where + ": epoch");
                    }
                    if (expect.containsKey("pointsSum")) {
                        BigInteger sum = BigInteger.ZERO;
                        for (SeriesPoint p : ((SeriesResponse) body).points()) {
                            sum = sum.add(new BigInteger(p.value()));
                        }
                        checkEq(sum.toString(), expect.get("pointsSum"), where + ": pointsSum");
                    }
                    if (expect.containsKey("pointsAtLeast")) {
                        long atLeast = ((Number) expect.get("pointsAtLeast")).longValue();
                        int got = ((SeriesResponse) body).points().size();
                        check(got >= atLeast, where + ": pointsAtLeast " + atLeast + ", got " + got);
                    }
                    if (expect.containsKey("containsInOrder")) {
                        List<Object> want = arr(expect.get("containsInOrder"));
                        int m = 0;
                        for (Object k : (List<?>) body) if (m < want.size() && k.equals(prefix + want.get(m))) m++;
                        checkEq(m, want.size(), where + ": containsInOrder");
                    }
                    if (expect.containsKey("usage")) {
                        Map<String, Object> u = obj(expect.get("usage"));
                        Usage usage = (Usage) body;
                        if (u.get("opsUsedAtLeast") instanceof Number n) {
                            check(usage.operations().used() >= n.longValue(),
                                    where + ": opsUsedAtLeast " + n.longValue()
                                            + ", got " + usage.operations().used());
                        }
                        if (u.get("countersUsedAtLeast") instanceof Number n) {
                            check(usage.counters().used() >= n.longValue(),
                                    where + ": countersUsedAtLeast " + n.longValue()
                                            + ", got " + usage.counters().used());
                        }
                        if (u.get("hasResetsAt") instanceof Boolean hasResetsAt) {
                            checkEq(usage.operations().resetsAt() != null, hasResetsAt, where + ": hasResetsAt");
                        }
                    }
                    if (expect.containsKey("memberValue")) {
                        checkEq(((MemberValue) body).memberValue(), expect.get("memberValue"), where + ": memberValue");
                    }
                    if (expect.containsKey("memberAccepted")) {
                        checkEq(((MemberValue) body).memberAccepted(), expect.get("memberAccepted"),
                                where + ": memberAccepted");
                    }
                    if (expect.containsKey("mode")) checkEq(modeOf(body), expect.get("mode"), where + ": mode");
                    if (expect.containsKey("order")) checkEq(((Leaderboard) body).order(), expect.get("order"),
                            where + ": order");
                    if (expect.containsKey("total")) checkEq(((Leaderboard) body).total(), expect.get("total"),
                            where + ": total");
                    if (expect.containsKey("memberCount")) {
                        checkEq(memberCountOf(body), ((Number) expect.get("memberCount")).longValue(),
                                where + ": memberCount");
                    }
                    if (expect.containsKey("rank")) {
                        checkEq(((MemberSnapshot) body).rank(), ((Number) expect.get("rank")).longValue(),
                                where + ": rank");
                    }
                    if (expect.containsKey("percentile")) {
                        checkEq(((MemberSnapshot) body).percentile(), expect.get("percentile"),
                                where + ": percentile");
                    }
                    if (expect.containsKey("metadata")) {
                        checkEq(metadataOf(body), expect.get("metadata"), where + ": metadata");
                    }
                    if (expect.containsKey("entries")) {
                        List<Object> want = arr(expect.get("entries"));
                        List<LeaderboardEntry> got = ((Leaderboard) body).entries();
                        checkEq(got.size(), want.size(), where + ": entries length");
                        for (int j = 0; j < want.size(); j++) {
                            Map<String, Object> we = obj(want.get(j));
                            LeaderboardEntry ge = got.get(j);
                            if (we.containsKey("rank")) {
                                checkEq(ge.rank(), ((Number) we.get("rank")).longValue(),
                                        where + ": entry " + j + " rank");
                            }
                            if (we.containsKey("member")) {
                                checkEq(ge.member(), we.get("member"), where + ": entry " + j + " member");
                            }
                            if (we.containsKey("value")) {
                                checkEq(ge.value(), we.get("value"), where + ": entry " + j + " value");
                            }
                            if (we.containsKey("metadata")) {
                                checkEq(ge.metadata(), we.get("metadata"), where + ": entry " + j + " metadata");
                            }
                        }
                    }
                }
                System.out.println("  ok   vector: " + c.get("name"));
            }
        }
    }

    private static Object runOp(CountersClient client, CounterHandle handle, Map<String, Object> op,
                                Instant occurredAt) {
        switch ((String) op.get("op")) {
            case "add":
                return handle.addNow((String) op.get("amount"), occurredAt);
            case "subtract":
                return handle.subtractNow((String) op.get("amount"), occurredAt);
            case "clear":
                return handle.clear();
            case "delete":
                handle.delete();
                return null;
            case "value":
                return handle.value();
            case "series": {
                Map<String, Object> p = obj(op.get("series"));
                return handle.series(new SeriesParams(
                        minutes(((Number) p.get("fromMin")).longValue()),
                        minutes(((Number) p.get("toMin")).longValue()),
                        (String) p.get("bucket"),
                        (String) p.get("tz"),
                        (Boolean) p.get("gapfill")));
            }
            case "list": {
                Map<String, Object> lp = op.get("list") != null ? obj(op.get("list")) : Map.of();
                int limit = lp.get("limit") != null ? (int) ((Number) lp.get("limit")).longValue() : 50;
                List<String> walked = new ArrayList<>();
                String cursor = null;
                do {
                    CounterPage page = client.list(cursor, limit);
                    for (Counter x : page.data()) walked.add(x.key());
                    cursor = page.nextCursor();
                } while (cursor != null);
                return walked;
            }
            case "usage":
                return client.usage();
            case "memberAdd":
                return handle.member((String) op.get("member"))
                        .add((String) op.get("amount"), memberWriteOptions(op, occurredAt));
            case "memberSubtract":
                return handle.member((String) op.get("member"))
                        .subtract((String) op.get("amount"), memberWriteOptions(op, occurredAt));
            case "memberSubmit":
                return handle.member((String) op.get("member"))
                        .submit((String) op.get("value"), submitOptions(op, occurredAt));
            case "memberGet":
                return handle.member((String) op.get("member"))
                        .get(new MemberGetParams(longParam(op, "epoch"), (String) op.get("order")));
            case "memberRemove":
                return handle.member((String) op.get("member")).remove();
            case "leaderboard":
                if (op.get("window") instanceof String window) {
                    return handle.windowLeaderboard(new WindowLeaderboardParams(window,
                            intParam(op, "limit"), intParam(op, "offset"),
                            (String) op.get("order"), longParam(op, "epoch")));
                }
                return handle.leaderboard(new LeaderboardParams(
                        intParam(op, "limit"), intParam(op, "offset"),
                        (String) op.get("order"), longParam(op, "epoch")));
            default:
                throw new IllegalStateException(
                        "vector op '" + op.get("op") + "' is not part of the SDK surface (case should be scope: http)");
        }
    }

    private static MemberWriteOptions memberWriteOptions(Map<String, Object> op, Instant occurredAt) {
        String metadata = (String) op.get("metadata");
        if (metadata == null && occurredAt == null) return null;
        return new MemberWriteOptions(metadata, occurredAt);
    }

    private static SubmitOptions submitOptions(Map<String, Object> op, Instant occurredAt) {
        String mode = (String) op.get("mode");
        String metadata = (String) op.get("metadata");
        if (mode == null && metadata == null && occurredAt == null) return null;
        return new SubmitOptions(mode, metadata, occurredAt);
    }

    private static Integer intParam(Map<String, Object> op, String key) {
        return op.get(key) instanceof Number n ? n.intValue() : null;
    }

    private static Long longParam(Map<String, Object> op, String key) {
        return op.get(key) instanceof Number n ? n.longValue() : null;
    }

    private static String keyOf(Object body) {
        if (body instanceof Counter c) return c.key();
        if (body instanceof ValueResponse v) return v.key();
        if (body instanceof Leaderboard v) return v.key();
        if (body instanceof MemberValue v) return v.key();
        if (body instanceof MemberRemoved v) return v.key();
        if (body instanceof MemberSnapshot v) return v.key();
        throw new AssertionError("vector body has no key: " + body);
    }

    private static String valueOf(Object body) {
        if (body instanceof Counter c) return c.value();
        if (body instanceof ValueResponse v) return v.value();
        if (body instanceof MemberValue v) return v.value();
        if (body instanceof MemberRemoved v) return v.value();
        if (body instanceof MemberSnapshot v) return v.value();
        throw new AssertionError("vector body has no value: " + body);
    }

    private static long epochOf(Object body) {
        if (body instanceof Counter c) return c.epoch();
        if (body instanceof ValueResponse v) return v.epoch();
        if (body instanceof Leaderboard v) return v.epoch();
        if (body instanceof MemberValue v) return v.epoch();
        if (body instanceof MemberRemoved v) return v.epoch();
        if (body instanceof MemberSnapshot v) return v.epoch();
        throw new AssertionError("vector body has no epoch: " + body);
    }

    private static String modeOf(Object body) {
        if (body instanceof Leaderboard v) return v.mode();
        if (body instanceof MemberValue v) return v.mode();
        if (body instanceof MemberSnapshot v) return v.mode();
        throw new AssertionError("vector body has no mode: " + body);
    }

    private static long memberCountOf(Object body) {
        if (body instanceof Leaderboard v) return v.memberCount();
        if (body instanceof MemberSnapshot v) return v.memberCount();
        throw new AssertionError("vector body has no memberCount: " + body);
    }

    private static String metadataOf(Object body) {
        if (body instanceof MemberSnapshot v) return v.metadata();
        throw new AssertionError("vector body has no metadata: " + body);
    }

    // ── 3. Surface-completeness gate: no public method may go undemonstrated ────────────────────

    private static void surfaceGate() {
        // The static factory is exercised as builder().build(); Object methods are not SDK surface.
        Set<String> skip = Set.of("builder", "equals", "hashCode", "toString");
        for (Class<?> cls : List.of(CountersClient.class, CounterHandle.class, MemberHandle.class, DerivedHandle.class)) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic() || skip.contains(m.getName())) continue;
                String tag = cls.getSimpleName() + "." + m.getName();
                check(INVOKED.contains(tag),
                        tag + " is a public method not demonstrated by this example app — "
                                + "demonstrate it here (recording it in INVOKED) or mark it internal");
            }
        }
        check(INVOKED.contains("CountersClient.builder().build()"), "builder().build() demonstrated");
    }
}
