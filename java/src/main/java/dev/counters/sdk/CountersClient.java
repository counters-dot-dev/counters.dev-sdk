package dev.counters.sdk;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The official Java client for the Counters arbitrary-precision counter service.
 *
 * <p>Amounts and values are arbitrary precision: inputs accept {@code long}, {@code String}, or
 * {@link BigInteger} and are sent over the wire as decimal strings (never JSON numbers). Buffered writes
 * ({@link CounterHandle#add}) are coalesced per counter and flushed in the background; call {@link #close()}
 * before exit to avoid losing buffered writes.
 *
 * <pre>{@code
 * try (CountersClient client = CountersClient.builder().apiKey("ck_...").build()) {
 *     CounterHandle registrations = client.counter("registrations");
 *     registrations.add(1);                  // buffered + coalesced
 *     Counter c = registrations.addNow(5);   // immediate; returns the new state
 * }
 * }</pre>
 */
public final class CountersClient implements ReadOnlyCountersClient {

    /** Production API endpoint. */
    public static final String DEFAULT_BASE_URL = "https://api.counters.dev/v1";

    private final Http http;
    private final Batcher batcher;
    private final boolean batchEnabled;
    private final Consumer<CountersException> onWriteError;

    private CountersClient(Builder b) {
        if (b.apiKey == null || b.apiKey.isEmpty()) {
            throw new IllegalArgumentException("CountersClient: apiKey is required");
        }
        this.http = new Http(b.baseUrl, b.apiKey, b.httpClient, b.maxRetries, b.backoffMillis,
                b.requestTimeoutMillis);
        this.batchEnabled = b.batchEnabled;
        this.onWriteError = b.onBatchError;
        this.batcher = new Batcher(this::submitBatch, b.maxBatchSize, b.batchIntervalMillis, b.onBatchError);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Configure a read-only client for a scoped publishable ({@code pk_}) token. */
    public static PublishableBuilder publishableBuilder() {
        return new PublishableBuilder();
    }

    /** Get a handle for a counter. Throws {@link CountersValidationException} if the key is invalid. */
    public CounterHandle counter(String key) {
        Validation.assertCounterKey(key);
        return new CounterHandle(this, key);
    }

    /** List counters in the organization (first page, default size). */
    public CounterPage list() {
        return list(null, null);
    }

    /** List counters in the organization. Pass a previous page's {@code nextCursor} to continue; null to omit. */
    public CounterPage list(String cursor, Integer limit) {
        Map<String, String> query = new LinkedHashMap<>();
        if (cursor != null) query.put("cursor", cursor);
        if (limit != null) query.put("limit", String.valueOf(limit));
        return toCounterPage(asMap(http.request("GET", "/counters", null, null, query)));
    }

    /** Current quota state for the organization. */
    public Usage usage() {
        return toUsage(asMap(http.request("GET", "/usage", null, null, null)));
    }

    /** Get a handle for a derived counter. Derived keys use the same shape as counter keys. */
    public DerivedHandle derived(String key) {
        Validation.assertCounterKey(key);
        return new DerivedHandle(this, key);
    }

    /** Flush any buffered operations now. */
    public void flush() {
        batcher.flush();
    }

    /** Flush and stop the background timer. Call before process exit to avoid losing buffered writes. */
    @Override
    public void close() {
        batcher.close();
    }

    // ---- internals used by CounterHandle ----

    void enqueue(String key, BigInteger delta) {
        if (batchEnabled) {
            batcher.enqueue(key, delta);
            return;
        }
        // Immediate mode: fire a single-op batch without buffering (fire-and-forget, like the TS/Go SDKs).
        // Match the buffered path: a write after close() has no worker to observe it — surface the misuse.
        if (batcher.isClosed()) throw new CountersException("cannot enqueue on a closed client");
        Operation op = delta.signum() >= 0
                ? new Operation(key, "add", delta.toString(), Idempotency.newKey(), null)
                : new Operation(key, "subtract", delta.negate().toString(), Idempotency.newKey(), null);
        CompletableFuture.runAsync(() -> {
            try {
                submitBatch(List.of(op));
            } catch (RuntimeException e) {
                // Fire-and-forget, like a background flush — so failures route to the same onError sink
                // (previously they were swallowed, which silently dropped counted writes).
                if (onWriteError != null) {
                    onWriteError.accept(CountersException.normalizeBatchFailure(e));
                }
            }
        });
    }

    Counter applyNow(String key, String op, BigInteger amount, OffsetDateTime occurredAt) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("amount", amount.toString());
        if (occurredAt != null) body.put("occurredAt", rfc3339Utc(occurredAt));
        Object res = http.request(
                "POST", "/counters/" + Http.encodePathSegment(key) + "/" + op, body, Idempotency.newKey(), null);
        return toCounter(asMap(res));
    }

    Counter clearCounter(String key) {
        Object res = http.request(
                "POST", "/counters/" + Http.encodePathSegment(key) + "/clear", null, Idempotency.newKey(), null);
        return toCounter(asMap(res));
    }

    void deleteCounter(String key) {
        http.request("DELETE", "/counters/" + Http.encodePathSegment(key), null, Idempotency.newKey(), null);
    }

    ValueResponse getValue(String key) {
        Map<String, Object> m = asMap(http.request(
                "GET", "/counters/" + Http.encodePathSegment(key) + "/value", null, null, null));
        return new ValueResponse(str(m, "key"), str(m, "value"), longVal(m, "epoch"));
    }

    SeriesResponse getSeries(String key, SeriesParams params) {
        Map<String, String> query = seriesQuery(params);
        Object res = http.request(
                "GET", "/counters/" + Http.encodePathSegment(key) + "/series", null, null, query);
        return toSeries(asMap(res));
    }

    MemberSeriesResponse getMemberSeries(String key, String member, SeriesParams params) {
        Validation.assertMemberKey(member);
        Map<String, String> query = seriesQuery(params);
        query.put("member", member);
        Object res = http.request(
                "GET", "/counters/" + Http.encodePathSegment(key) + "/series", null, null, query);
        return toMemberSeries(asMap(res));
    }

    MemberGroupSeriesResponse getGroupSeries(String key, SeriesParams params) {
        Map<String, String> query = seriesQuery(params);
        query.put("groupBy", "member");
        Object res = http.request(
                "GET", "/counters/" + Http.encodePathSegment(key) + "/series", null, null, query);
        return toMemberGroupSeries(asMap(res));
    }

    Leaderboard getLeaderboard(String key, LeaderboardParams params) {
        Map<String, String> query = leaderboardQuery(params);
        Object res = http.request(
                "GET", "/counters/" + Http.encodePathSegment(key) + "/leaderboard", null, null, query);
        return toLeaderboard(asMap(res));
    }

    WindowLeaderboard getWindowLeaderboard(String key, WindowLeaderboardParams params) {
        if (params == null) throw new CountersValidationException("window leaderboard params are required");
        Validation.assertWindow(params.window());
        Map<String, String> query = leaderboardQuery(new LeaderboardParams(
                params.limit(), params.offset(), params.order(), params.epoch()));
        query.put("window", params.window());
        Object res = http.request(
                "GET", "/counters/" + Http.encodePathSegment(key) + "/leaderboard", null, null, query);
        return toWindowLeaderboard(asMap(res));
    }

    MemberSnapshot getMember(String key, String member, MemberGetParams params) {
        Map<String, String> query = new LinkedHashMap<>();
        if (params != null) {
            if (params.epoch() != null) query.put("epoch", String.valueOf(params.epoch()));
            if (params.order() != null) query.put("order", params.order());
        }
        Object res = http.request(
                "GET",
                "/counters/" + Http.encodePathSegment(key) + "/members/" + Http.encodePathSegment(member),
                null,
                null,
                query);
        return toMemberSnapshot(asMap(res));
    }

    MemberRemoved removeMember(String key, String member) {
        Object res = http.request(
                "DELETE",
                "/counters/" + Http.encodePathSegment(key) + "/members/" + Http.encodePathSegment(member),
                null,
                Idempotency.newKey(),
                null);
        return toMemberRemoved(asMap(res));
    }

    MemberValue applyMember(String key, String member, String op, BigInteger amount, MemberWriteOptions opts) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("amount", amount.toString());
        if (opts != null) {
            if (opts.metadata() != null) body.put("metadata", opts.metadata());
            if (opts.occurredAt() != null) body.put("occurredAt", rfc3339Utc(opts.occurredAt()));
        }
        Object res = http.request(
                "POST",
                "/counters/" + Http.encodePathSegment(key)
                        + "/members/" + Http.encodePathSegment(member) + "/" + op,
                body,
                Idempotency.newKey(),
                null);
        return toMemberValue(asMap(res));
    }

    MemberValue submitMember(String key, String member, BigInteger value, SubmitOptions opts) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("value", value.toString());
        if (opts != null) {
            if (opts.mode() != null) body.put("mode", opts.mode());
            if (opts.metadata() != null) body.put("metadata", opts.metadata());
            if (opts.occurredAt() != null) body.put("occurredAt", rfc3339Utc(opts.occurredAt()));
        }
        Object res = http.request(
                "POST",
                "/counters/" + Http.encodePathSegment(key)
                        + "/members/" + Http.encodePathSegment(member) + "/submit",
                body,
                Idempotency.newKey(),
                null);
        return toMemberValue(asMap(res));
    }

    DerivedValueResponse getDerivedValue(String key) {
        Object res = http.request("GET", "/derived/" + Http.encodePathSegment(key) + "/value", null, null, null);
        return toDerivedValue(asMap(res));
    }

    DerivedSeriesResponse getDerivedSeries(String key, DerivedSeriesParams params) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", rfc3339Utc(params.from()));
        query.put("to", rfc3339Utc(params.to()));
        query.put("bucket", params.bucket());
        if (params.tz() != null) query.put("tz", params.tz());
        Object res = http.request(
                "GET", "/derived/" + Http.encodePathSegment(key) + "/series", null, null, query);
        return toDerivedSeries(asMap(res));
    }

    void submitBatch(List<Operation> ops) {
        List<Map<String, Object>> jsonOps = new ArrayList<>(ops.size());
        for (Operation op : ops) jsonOps.add(op.toJson());
        Object res = http.request("POST", "/batch", Map.of("operations", jsonOps), null, null);
        checkBatchResults(res);
    }

    /**
     * A 200 from /batch only means the batch was accepted; each op carries its own status. Surface a
     * per-op {@code "error"} (e.g. a counter/quota cap) instead of silently dropping the buffered write.
     *
     * <p>a per-op problem carrying a {@code status} surfaces as a
     * {@link CountersApiException} with that status, exactly as if the operation had failed standalone. A
     * per-op problem with no status (or no problem object at all) has no failing HTTP status to carry —
     * never fabricate one (no status 0): the problem the SDK cannot faithfully represent is rejected
     * client-side as a {@link CountersValidationException}.
     */
    private static void checkBatchResults(Object res) {
        if (!(res instanceof Map<?, ?> m) || !(m.get("results") instanceof List<?> results)) return;
        int failed = 0;
        Map<?, ?> first = null;
        for (Object r : results) {
            if (r instanceof Map<?, ?> rm && "error".equals(rm.get("status"))) {
                failed++;
                if (first == null) first = rm;
            }
        }
        if (first == null) return;
        Integer status = null;
        String title = "error";
        if (first.get("error") instanceof Map<?, ?> err) {
            if (err.get("status") instanceof Number n) status = n.intValue();
            if (err.get("title") instanceof String t) title = t;
        }
        String msg = "batch: " + failed + " operation(s) failed (" + first.get("counterKey") + ": " + title + ")";
        if (status != null) throw new CountersApiException(status, msg);
        throw new CountersValidationException(msg + "; per-op problem carries no status");
    }

    // ---- JSON mapping (tolerant of missing optional fields) ----

    private static String rfc3339Utc(OffsetDateTime t) {
        return t.toInstant().toString(); // RFC 3339, UTC, 'Z' suffix
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new CountersException(
                "unexpected response shape: " + (o == null ? "empty body" : o.getClass().getSimpleName()));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static Instant instant(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : Instant.parse(v.toString());
    }

    private static long longVal(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static Long nullableLong(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : null;
    }

    private static boolean boolVal(Map<String, Object> m, String key) {
        return m.get(key) instanceof Boolean b && b;
    }

    private static Map<String, String> seriesQuery(SeriesParams params) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", rfc3339Utc(params.from()));
        query.put("to", rfc3339Utc(params.to()));
        query.put("bucket", params.bucket());
        if (params.mode() != null) query.put("mode", params.mode());
        if (params.tz() != null) query.put("tz", params.tz());
        if (Boolean.TRUE.equals(params.gapfill())) query.put("gapfill", "true");
        return query;
    }

    private static Map<String, String> leaderboardQuery(LeaderboardParams params) {
        Map<String, String> query = new LinkedHashMap<>();
        if (params == null) return query;
        if (params.limit() != null) query.put("limit", String.valueOf(params.limit()));
        if (params.offset() != null) query.put("offset", String.valueOf(params.offset()));
        if (params.order() != null) query.put("order", params.order());
        if (params.epoch() != null) query.put("epoch", String.valueOf(params.epoch()));
        return query;
    }

    private static Counter toCounter(Map<String, Object> m) {
        return new Counter(str(m, "key"), str(m, "value"), longVal(m, "epoch"),
                instant(m, "createdAt"), instant(m, "updatedAt"));
    }

    private static CounterPage toCounterPage(Map<String, Object> m) {
        List<Counter> data = new ArrayList<>();
        for (Object item : asList(m.get("data"))) data.add(toCounter(asMap(item)));
        return new CounterPage(List.copyOf(data), str(m, "nextCursor"));
    }

    private static SeriesResponse toSeries(Map<String, Object> m) {
        Map<String, Object> range = m.get("range") instanceof Map ? asMap(m.get("range")) : Map.of();
        List<SeriesPoint> points = new ArrayList<>();
        for (Object item : asList(m.get("points"))) {
            Map<String, Object> pm = asMap(item);
            points.add(new SeriesPoint(instant(pm, "t"), str(pm, "v")));
        }
        return new SeriesResponse(str(m, "counterKey"), str(m, "bucket"), str(m, "mode"), str(m, "tz"),
                new SeriesResponse.Range(str(range, "from"), str(range, "to")), List.copyOf(points));
    }

    private static Usage toUsage(Map<String, Object> m) {
        Map<String, Object> ops = m.get("ops") instanceof Map ? asMap(m.get("ops")) : Map.of();
        Map<String, Object> counters = m.get("counters") instanceof Map ? asMap(m.get("counters")) : Map.of();
        Map<String, Object> limits = m.get("limits") instanceof Map ? asMap(m.get("limits")) : Map.of();
        return new Usage(
                str(m, "month"),
                new Usage.Ops(longVal(ops, "used"), nullableLong(ops, "quota"), str(ops, "resetsAt")),
                new Usage.Counters(longVal(counters, "used"), longVal(counters, "max")),
                new Usage.Limits(
                        longVal(limits, "rateLimitRps"),
                        longVal(limits, "maxCounters"),
                        nullableLong(limits, "monthlyOpsQuota")));
    }

    private static Leaderboard toLeaderboard(Map<String, Object> m) {
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (Object item : asList(m.get("entries"))) {
            Map<String, Object> em = asMap(item);
            entries.add(new LeaderboardEntry(longVal(em, "rank"), str(em, "member"), str(em, "value"),
                    str(em, "metadata"), instant(em, "updatedAt")));
        }
        return new Leaderboard(
                str(m, "key"),
                str(m, "mode"),
                longVal(m, "epoch"),
                str(m, "order"),
                str(m, "total"),
                longVal(m, "memberCount"),
                longVal(m, "limit"),
                longVal(m, "offset"),
                List.copyOf(entries));
    }

    private static WindowLeaderboard toWindowLeaderboard(Map<String, Object> m) {
        List<WindowEntry> entries = new ArrayList<>();
        for (Object item : asList(m.get("entries"))) {
            Map<String, Object> em = asMap(item);
            entries.add(new WindowEntry(longVal(em, "rank"), str(em, "member"), str(em, "value")));
        }
        return new WindowLeaderboard(
                str(m, "key"),
                str(m, "mode"),
                str(m, "window"),
                str(m, "order"),
                str(m, "total"),
                longVal(m, "memberCount"),
                longVal(m, "limit"),
                longVal(m, "offset"),
                str(m, "effectiveStart"),
                str(m, "effectiveEnd"),
                List.copyOf(entries));
    }

    private static MemberValue toMemberValue(Map<String, Object> m) {
        return new MemberValue(str(m, "key"), str(m, "member"), str(m, "memberValue"),
                boolVal(m, "memberAccepted"), str(m, "mode"), longVal(m, "epoch"), str(m, "value"));
    }

    private static MemberRemoved toMemberRemoved(Map<String, Object> m) {
        return new MemberRemoved(str(m, "key"), str(m, "member"), longVal(m, "epoch"), str(m, "value"));
    }

    private static MemberSnapshot toMemberSnapshot(Map<String, Object> m) {
        return new MemberSnapshot(
                str(m, "key"),
                str(m, "member"),
                str(m, "value"),
                str(m, "metadata"),
                longVal(m, "rank"),
                str(m, "percentile"),
                longVal(m, "memberCount"),
                str(m, "mode"),
                longVal(m, "epoch"),
                instant(m, "updatedAt"));
    }

    private static MemberSeriesResponse toMemberSeries(Map<String, Object> m) {
        Map<String, Object> range = m.get("range") instanceof Map ? asMap(m.get("range")) : Map.of();
        List<SeriesPoint> points = new ArrayList<>();
        for (Object item : asList(m.get("points"))) {
            Map<String, Object> pm = asMap(item);
            points.add(new SeriesPoint(instant(pm, "t"), str(pm, "v")));
        }
        return new MemberSeriesResponse(str(m, "counterKey"), str(m, "member"), str(m, "bucket"),
                str(m, "mode"), str(m, "tz"),
                new SeriesResponse.Range(str(range, "from"), str(range, "to")), List.copyOf(points));
    }

    private static MemberGroupSeriesResponse toMemberGroupSeries(Map<String, Object> m) {
        Map<String, Object> range = m.get("range") instanceof Map ? asMap(m.get("range")) : Map.of();
        List<MemberSeriesEntry> series = new ArrayList<>();
        for (Object item : asList(m.get("series"))) {
            Map<String, Object> sm = asMap(item);
            List<SeriesPoint> points = new ArrayList<>();
            for (Object rawPoint : asList(sm.get("points"))) {
                Map<String, Object> pm = asMap(rawPoint);
                points.add(new SeriesPoint(instant(pm, "t"), str(pm, "v")));
            }
            series.add(new MemberSeriesEntry(str(sm, "member"), List.copyOf(points)));
        }
        return new MemberGroupSeriesResponse(str(m, "counterKey"), str(m, "bucket"), str(m, "tz"),
                new SeriesResponse.Range(str(range, "from"), str(range, "to")), List.copyOf(series));
    }

    private static DerivedValueResponse toDerivedValue(Map<String, Object> m) {
        Map<String, String> inputs = new LinkedHashMap<>();
        if (m.get("inputs") instanceof Map<?, ?> rawInputs) {
            for (Map.Entry<?, ?> e : rawInputs.entrySet()) {
                inputs.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        }
        return new DerivedValueResponse(str(m, "key"), str(m, "value"), longVal(m, "scale"),
                Map.copyOf(inputs), str(m, "reason"));
    }

    private static DerivedSeriesResponse toDerivedSeries(Map<String, Object> m) {
        Map<String, Object> range = m.get("range") instanceof Map ? asMap(m.get("range")) : Map.of();
        List<DerivedSeriesPoint> points = new ArrayList<>();
        for (Object item : asList(m.get("points"))) {
            Map<String, Object> pm = asMap(item);
            points.add(new DerivedSeriesPoint(str(pm, "t"), str(pm, "v")));
        }
        return new DerivedSeriesResponse(str(m, "key"), str(m, "bucket"), str(m, "tz"), longVal(m, "scale"),
                new SeriesResponse.Range(str(range, "from"), str(range, "to")), List.copyOf(points));
    }

    /** Fluent configuration for {@link CountersClient}. Only {@link #apiKey} is required. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private HttpClient httpClient; // null -> sensible default
        private int maxRetries = 3;
        private long backoffMillis = 200;
        private long requestTimeoutMillis = 30_000;
        private boolean batchEnabled = true;
        private int maxBatchSize = 100;
        private long batchIntervalMillis = 1000;
        private Consumer<CountersException> onBatchError;

        private Builder() {}

        /** Organization API key (required). Sent as {@code Authorization: Bearer <key>}. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** API base URL, default {@value CountersClient#DEFAULT_BASE_URL}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Inject a custom {@link HttpClient} (useful in tests). */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Retries after the first attempt on connect errors and 429/5xx, default 3. */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
            this.maxRetries = maxRetries;
            return this;
        }

        /** Base backoff in milliseconds, doubled per retry, default 200. */
        public Builder backoffMillis(long backoffMillis) {
            this.backoffMillis = backoffMillis;
            return this;
        }

        /**
         * Per-attempt request timeout in milliseconds, default 30000. A timed-out attempt is retried
         * like a network error; exhausted retries throw {@link CountersTransportException}.
         */
        public Builder requestTimeoutMillis(long requestTimeoutMillis) {
            if (requestTimeoutMillis <= 0) throw new IllegalArgumentException("requestTimeoutMillis must be > 0");
            this.requestTimeoutMillis = requestTimeoutMillis;
            return this;
        }

        /** Enable/disable buffering of {@code add}/{@code subtract} (default true). When disabled, each write fires immediately. */
        public Builder batchEnabled(boolean batchEnabled) {
            this.batchEnabled = batchEnabled;
            return this;
        }

        /** Buffered distinct-counter count that triggers an early flush, default 100. */
        public Builder maxBatchSize(int maxBatchSize) {
            if (maxBatchSize < 1) throw new IllegalArgumentException("maxBatchSize must be >= 1");
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /** Background flush interval in milliseconds, default 1000. {@code <= 0} disables the timer (flush manually or via maxBatchSize). */
        public Builder batchIntervalMillis(long batchIntervalMillis) {
            this.batchIntervalMillis = batchIntervalMillis;
            return this;
        }

        /**
         * Sink for errors from fire-and-forget writes — background flushes and, when
         * {@link #batchEnabled(boolean) batching is disabled}, immediate-mode writes. These run
         * off-thread, so without this hook they are silent. Every delivered error is a
         * {@link CountersException} and can be matched against its API, transport, and validation subtypes.
         */
        public Builder onBatchError(Consumer<CountersException> onBatchError) {
            this.onBatchError = onBatchError;
            return this;
        }

        public CountersClient build() {
            return new CountersClient(this);
        }
    }

    /**
     * Transport-only configuration for a scoped publishable ({@code pk_}) token. Its build result has
     * no write, organization-wide, or derived-counter operations.
     */
    public static final class PublishableBuilder {
        private final Builder delegate = new Builder();

        private PublishableBuilder() {}

        /** Scoped publishable token (required). Sent as {@code Authorization: Bearer <token>}. */
        public PublishableBuilder apiKey(String apiKey) {
            delegate.apiKey(apiKey);
            return this;
        }

        /** API base URL, default {@value CountersClient#DEFAULT_BASE_URL}. */
        public PublishableBuilder baseUrl(String baseUrl) {
            delegate.baseUrl(baseUrl);
            return this;
        }

        /** Inject a custom {@link HttpClient} (useful in tests). */
        public PublishableBuilder httpClient(HttpClient httpClient) {
            delegate.httpClient(httpClient);
            return this;
        }

        /** Retries after the first attempt on connect errors and 429/5xx, default 3. */
        public PublishableBuilder maxRetries(int maxRetries) {
            delegate.maxRetries(maxRetries);
            return this;
        }

        /** Base backoff in milliseconds, doubled per retry, default 200. */
        public PublishableBuilder backoffMillis(long backoffMillis) {
            delegate.backoffMillis(backoffMillis);
            return this;
        }

        /** Per-attempt request timeout in milliseconds, default 30000. */
        public PublishableBuilder requestTimeoutMillis(long requestTimeoutMillis) {
            delegate.requestTimeoutMillis(requestTimeoutMillis);
            return this;
        }

        /** Build a client whose static type exposes only scoped read operations. */
        public ReadOnlyCountersClient build() {
            return delegate.build();
        }
    }
}
