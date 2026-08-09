package dev.counters.sdk;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
    private final Consumer<WriteFailure> onWriteError;

    private CountersClient(Builder b) {
        Validation.assertApiKey(b.apiKey);
        Validation.assertBaseUrl(b.baseUrl);
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

    /** Create or verify the complete known counter set with bounded, per-key results. */
    public DeclareCountersResponse declare(DeclareCountersRequest request) {
        if (request == null) throw new CountersValidationException("declare request is required");
        List<CounterDeclaration> declarations = request.counters();
        if (declarations == null || declarations.isEmpty() || declarations.size() > 1000) {
            throw new CountersValidationException("declare counters must contain between 1 and 1000 entries");
        }
        List<Map<String, Object>> wireDeclarations = new ArrayList<>(declarations.size());
        for (int i = 0; i < declarations.size(); i++) {
            CounterDeclaration declaration = declarations.get(i);
            if (declaration == null) {
                throw new CountersValidationException("declare counters[" + i + "] is required");
            }
            if (declaration.key() == null) {
                throw new CountersValidationException("declare counters[" + i + "].key is required");
            }
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("key", declaration.key());
            if (declaration.memberMode() != null) wire.put("memberMode", declaration.memberMode());
            if (declaration.memberSeriesEnabled() != null) {
                wire.put("memberSeriesEnabled", declaration.memberSeriesEnabled());
            }
            wireDeclarations.add(wire);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("counters", wireDeclarations);
        return toDeclareCountersResponse(asMap(http.request("POST", "/counters", body, null, null)));
    }

    /** Read the organization-wide implicit-create policy and its compare-and-set version. */
    public CounterWritePolicy getCounterWritePolicy() {
        return toCounterWritePolicy(asMap(http.request("GET", "/counter-write-policy", null, null, null)));
    }

    /** Compare-and-set the organization-wide implicit-create policy. */
    public CounterWritePolicy setCounterWritePolicy(SetCounterWritePolicyRequest request) {
        if (request == null) throw new CountersValidationException("counter write policy request is required");
        if (request.undeclaredCounterWrites() == null) {
            throw new CountersValidationException("undeclaredCounterWrites is required");
        }
        if (request.expectedVersion() < 0) {
            throw new CountersValidationException("expectedVersion must be zero or greater");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("undeclaredCounterWrites", request.undeclaredCounterWrites().wireValue());
        body.put("expectedVersion", request.expectedVersion());
        return toCounterWritePolicy(asMap(http.request("PUT", "/counter-write-policy", body, null, null)));
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
        if (batcher.isClosed()) throw new CountersValidationException("cannot enqueue on a closed client");
        Operation op = delta.signum() >= 0
                ? new Operation(key, "add", delta.toString(), Idempotency.newKey(), null)
                : new Operation(key, "subtract", delta.negate().toString(), Idempotency.newKey(), null);
        CompletableFuture.runAsync(() -> {
            try {
                List<WriteFailure> failures = submitBatch(List.of(op));
                if (onWriteError != null) failures.forEach(onWriteError);
            } catch (Throwable failure) {
                // Fire-and-forget, like a background flush — so failures route to the same onError sink.
                if (onWriteError != null) onWriteError.accept(
                        WriteFailure.from(op, CountersException.normalizeBatchFailure(failure)));
            }
        });
    }

    Counter applyNow(String key, String op, BigInteger amount, Instant occurredAt, String idempotencyKey) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("amount", amount.toString());
        if (occurredAt != null) body.put("occurredAt", occurredAt.toString());
        Object res = http.request(
                "POST", "/counters/" + Http.encodePathSegment(key) + "/" + op, body,
                Idempotency.keyOrNew(idempotencyKey), null);
        return toCounter(asMap(res));
    }

    Counter clearCounter(String key, String idempotencyKey) {
        Object res = http.request(
                "POST", "/counters/" + Http.encodePathSegment(key) + "/clear", null,
                Idempotency.keyOrNew(idempotencyKey), null);
        return toCounter(asMap(res));
    }

    void deleteCounter(String key, String idempotencyKey) {
        http.request("DELETE", "/counters/" + Http.encodePathSegment(key), null,
                Idempotency.keyOrNew(idempotencyKey), null);
    }

    ValueResponse getValue(String key) {
        Map<String, Object> m = asMap(http.request(
                "GET", "/counters/" + Http.encodePathSegment(key) + "/value", null, null, null));
        return new ValueResponse(str(m, "key"), str(m, "value"), longVal(m, "epoch"));
    }

    Counter getCounter(String key) {
        return toCounter(asMap(http.request(
                "GET", "/counters/" + Http.encodePathSegment(key), null, null, null)));
    }

    MemberSeriesConfig setMemberSeries(String key, boolean enabled, Long expectedEpoch) {
        if (expectedEpoch != null && expectedEpoch < 0) {
            throw new CountersValidationException("expectedEpoch must be non-negative");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        if (expectedEpoch != null) body.put("expectedEpoch", expectedEpoch);
        Object response = http.request(
                "PUT", "/counters/" + Http.encodePathSegment(key) + "/member-series",
                body, null, null);
        return toMemberSeriesConfig(asMap(response));
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

    MemberRemoved removeMember(String key, String member, String idempotencyKey) {
        Object res = http.request(
                "DELETE",
                "/counters/" + Http.encodePathSegment(key) + "/members/" + Http.encodePathSegment(member),
                null,
                Idempotency.keyOrNew(idempotencyKey),
                null);
        return toMemberRemoved(asMap(res));
    }

    MemberValue applyMember(String key, String member, String op, BigInteger amount, MemberWriteOptions opts) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("amount", amount.toString());
        if (opts != null) {
            if (opts.metadata() != null) body.put("metadata", opts.metadata());
            if (opts.occurredAt() != null) body.put("occurredAt", opts.occurredAt().toString());
        }
        Object res = http.request(
                "POST",
                "/counters/" + Http.encodePathSegment(key)
                        + "/members/" + Http.encodePathSegment(member) + "/" + op,
                body,
                Idempotency.keyOrNew(opts == null ? null : opts.idempotencyKey()),
                null);
        return toMemberValue(asMap(res));
    }

    MemberValue submitMember(String key, String member, BigInteger value, SubmitOptions opts) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("value", value.toString());
        if (opts != null) {
            if (opts.mode() != null) body.put("mode", opts.mode());
            if (opts.metadata() != null) body.put("metadata", opts.metadata());
            if (opts.occurredAt() != null) body.put("occurredAt", opts.occurredAt().toString());
        }
        Object res = http.request(
                "POST",
                "/counters/" + Http.encodePathSegment(key)
                        + "/members/" + Http.encodePathSegment(member) + "/submit",
                body,
                Idempotency.keyOrNew(opts == null ? null : opts.idempotencyKey()),
                null);
        return toMemberValue(asMap(res));
    }

    DerivedValueResponse getDerivedValue(String key) {
        Object res = http.request("GET", "/derived/" + Http.encodePathSegment(key) + "/value", null, null, null);
        return toDerivedValue(asMap(res));
    }

    DerivedSeriesResponse getDerivedSeries(String key, DerivedSeriesParams params) {
        if (params == null) throw new CountersValidationException("derived series params are required");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", params.from().toString());
        query.put("to", params.to().toString());
        query.put("bucket", params.bucket());
        if (params.timeZone() != null) query.put("tz", params.timeZone());
        Object res = http.request(
                "GET", "/derived/" + Http.encodePathSegment(key) + "/series", null, null, query);
        return toDerivedSeries(asMap(res));
    }

    List<WriteFailure> submitBatch(List<Operation> ops) {
        List<Map<String, Object>> jsonOps = new ArrayList<>(ops.size());
        for (Operation op : ops) jsonOps.add(op.toJson());
        Object res = http.request("POST", "/batch", Map.of("operations", jsonOps), null, null);
        return checkBatchResults(res, ops);
    }

    /**
     * A 200 from /batch only means the batch was accepted; each op carries its own status. Surface a
     * per-op {@code "error"} (e.g. a counter/quota cap) instead of silently dropping the buffered write.
     *
     * <p>A per-op problem carrying an integral {@code status} in the real HTTP range surfaces as a
     * {@link CountersApiException} with that status, exactly as if the operation had failed standalone. A
     * per-op problem with no status (or no problem object at all) has no failing HTTP status to carry —
     * never fabricate one (no status 0): the problem the SDK cannot faithfully represent is rejected
     * client-side as a {@link CountersValidationException}.
     */
    static List<WriteFailure> checkBatchResults(Object res, List<Operation> ops) {
        if (!(res instanceof Map<?, ?> m) || !(m.get("results") instanceof List<?> results)) {
            throw new CountersValidationException("batch response does not contain a results array");
        }

        if (results.size() != ops.size()) {
            throw new CountersValidationException(
                    "batch response result count does not match submitted operation count: expected "
                            + ops.size() + ", got " + results.size());
        }

        Map<String, Operation> operationsByKey = new LinkedHashMap<>();
        for (Operation operation : ops) {
            if (operationsByKey.put(operation.counterKey(), operation) != null) {
                throw new CountersValidationException(
                        "submitted batch contains duplicate counter " + operation.counterKey());
            }
        }

        List<Map<?, ?>> validatedResults = new ArrayList<>(results.size());
        Map<String, Map<?, ?>> resultsByKey = new LinkedHashMap<>();
        for (int i = 0; i < results.size(); i++) {
            Object raw = results.get(i);
            if (!(raw instanceof Map<?, ?> result)) {
                throw new CountersValidationException("batch result " + i + " is not an object");
            }

            if (!(result.get("counterKey") instanceof String resultKey) || resultKey.isBlank()) {
                throw new CountersValidationException(
                        "batch result " + i + " does not contain a non-blank counterKey");
            }
            if (!operationsByKey.containsKey(resultKey)) {
                throw new CountersValidationException(
                        "batch response contains a result for unknown counter " + resultKey);
            }
            if (resultsByKey.put(resultKey, result) != null) {
                throw new CountersValidationException(
                        "batch response contains duplicate results for counter " + resultKey);
            }

            Object status = result.get("status");
            if (!("applied".equals(status) || "deduplicated".equals(status) || "error".equals(status))) {
                throw new CountersValidationException(
                        "batch result " + i + " has an invalid status: " + status);
            }
            validatedResults.add(result);
        }

        for (String counterKey : operationsByKey.keySet()) {
            if (!resultsByKey.containsKey(counterKey)) {
                throw new CountersValidationException(
                        "batch response does not contain a result for submitted counter " + counterKey);
            }
        }

        List<WriteFailure> failures = new ArrayList<>();
        for (Map<?, ?> result : validatedResults) {
            if (!"error".equals(result.get("status"))) continue;

            String resultKey = (String) result.get("counterKey");
            Operation operation = operationsByKey.get(resultKey);

            Integer status = null;
            String title = "error";
            if (result.get("error") instanceof Map<?, ?> error) {
                status = validHttpStatus(error.get("status"));
                if (error.get("title") instanceof String t) title = t;
            }
            String message = "batch operation failed (" + operation.counterKey() + ": " + title + ")";
            CountersException error = status == null
                    ? new CountersValidationException(message + "; per-op problem carries no valid HTTP status")
                    : new CountersApiException(status, message);
            failures.add(WriteFailure.from(operation, error));
        }
        return List.copyOf(failures);
    }

    private static Integer validHttpStatus(Object raw) {
        long status;
        if (raw instanceof BigInteger big) {
            if (big.bitLength() > 63) return null;
            status = big.longValue();
        } else if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            status = ((Number) raw).longValue();
        } else if (raw instanceof Float || raw instanceof Double) {
            double decimal = ((Number) raw).doubleValue();
            if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)) return null;
            if (decimal < 100 || decimal > 599) return null;
            status = (long) decimal;
        } else {
            return null;
        }
        return status >= 100 && status <= 599 ? (int) status : null;
    }

    // ---- JSON mapping (tolerant of missing optional fields) ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new CountersValidationException(
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
        if (v == null) return null;
        try {
            return Instant.parse(v.toString());
        } catch (DateTimeParseException e) {
            throw new CountersValidationException("response field " + key + " is not a valid timestamp", e);
        }
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

    private static Boolean nullableBoolean(Map<String, Object> m, String key) {
        return m.get(key) instanceof Boolean b ? b : null;
    }

    private static Map<String, String> seriesQuery(SeriesParams params) {
        if (params == null) throw new CountersValidationException("series params are required");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("from", params.from().toString());
        query.put("to", params.to().toString());
        query.put("bucket", params.bucket());
        if (params.mode() != null) query.put("mode", params.mode());
        if (params.timeZone() != null) query.put("tz", params.timeZone());
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
        return new Counter(
                str(m, "key"),
                str(m, "value"),
                longVal(m, "epoch"),
                str(m, "memberMode"),
                nullableBoolean(m, "memberSeriesEnabled"),
                instant(m, "memberSeriesEnabledAt"),
                str(m, "memberSeriesEnabledBy"),
                nullableLong(m, "memberCount"),
                instant(m, "createdAt"),
                instant(m, "updatedAt"));
    }

    private static DeclareCountersResponse toDeclareCountersResponse(Map<String, Object> m) {
        List<CounterDeclarationResult> results = new ArrayList<>();
        for (Object item : asList(m.get("results"))) {
            Map<String, Object> result = asMap(item);
            String status = str(result, "status");
            if (!("created".equals(status) || "unchanged".equals(status) || "error".equals(status))) {
                throw new CountersValidationException("declaration result has an invalid status: " + status);
            }
            results.add(new CounterDeclarationResult(
                    str(result, "key"),
                    status,
                    nullableLong(result, "epoch"),
                    str(result, "memberMode"),
                    nullableBoolean(result, "memberSeriesEnabled"),
                    instant(result, "memberSeriesEnabledAt"),
                    str(result, "memberSeriesEnabledBy"),
                    nullableLong(result, "memberCount"),
                    toProblem(result.get("error"))));
        }
        return new DeclareCountersResponse(
                List.copyOf(results),
                toCounterWritePolicy(asMap(m.get("policy"))));
    }

    private static CounterWritePolicy toCounterWritePolicy(Map<String, Object> m) {
        return new CounterWritePolicy(
                UndeclaredCounterWrites.fromWire(str(m, "undeclaredCounterWrites")),
                longVal(m, "version"),
                boolVal(m, "explicit"),
                instant(m, "updatedAt"),
                str(m, "updatedBy"));
    }

    private static Problem toProblem(Object value) {
        if (value == null) return null;
        Map<String, Object> m = asMap(value);
        Long status = nullableLong(m, "status");
        return new Problem(
                str(m, "type"),
                str(m, "title"),
                status == null ? null : status.intValue(),
                str(m, "detail"),
                str(m, "instance"));
    }

    private static MemberSeriesConfig toMemberSeriesConfig(Map<String, Object> m) {
        return new MemberSeriesConfig(
                str(m, "key"),
                boolVal(m, "enabled"),
                longVal(m, "memberCount"),
                longVal(m, "maxMembersWithSeries"),
                str(m, "mode"),
                instant(m, "enabledAt"),
                str(m, "enabledBy"));
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
                new SeriesResponse.Range(instant(range, "from"), instant(range, "to")), List.copyOf(points));
    }

    private static Usage toUsage(Map<String, Object> m) {
        Map<String, Object> ops = m.get("ops") instanceof Map ? asMap(m.get("ops")) : Map.of();
        Map<String, Object> counters = m.get("counters") instanceof Map ? asMap(m.get("counters")) : Map.of();
        Map<String, Object> limits = m.get("limits") instanceof Map ? asMap(m.get("limits")) : Map.of();
        return new Usage(
                str(m, "month"),
                new Usage.Operations(longVal(ops, "used"), nullableLong(ops, "quota"), instant(ops, "resetsAt")),
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
                instant(m, "effectiveStart"),
                instant(m, "effectiveEnd"),
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
                new SeriesResponse.Range(instant(range, "from"), instant(range, "to")), List.copyOf(points));
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
        return new MemberGroupSeriesResponse(str(m, "counterKey"), str(m, "bucket"), str(m, "mode"),
                str(m, "tz"),
                new SeriesResponse.Range(instant(range, "from"), instant(range, "to")),
                longVal(m, "memberCount"), longVal(m, "selectedCount"), boolVal(m, "truncated"),
                List.copyOf(series));
    }

    private static DerivedValueResponse toDerivedValue(Map<String, Object> m) {
        Map<String, String> inputs = new LinkedHashMap<>();
        if (m.get("inputs") instanceof Map<?, ?> rawInputs) {
            for (Map.Entry<?, ?> e : rawInputs.entrySet()) {
                if (e.getValue() == null) {
                    throw new CountersValidationException("derived response input value must not be null");
                }
                inputs.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
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
            points.add(new DerivedSeriesPoint(instant(pm, "t"), str(pm, "v")));
        }
        return new DerivedSeriesResponse(str(m, "key"), str(m, "bucket"), str(m, "tz"), longVal(m, "scale"),
                new SeriesResponse.Range(instant(range, "from"), instant(range, "to")), List.copyOf(points));
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
        private Consumer<WriteFailure> onBatchError;

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
            if (maxRetries < 0) throw new CountersValidationException("maxRetries must be >= 0");
            this.maxRetries = maxRetries;
            return this;
        }

        /** Base backoff in milliseconds, doubled per retry, default 200. */
        public Builder backoffMillis(long backoffMillis) {
            if (backoffMillis < 0) throw new CountersValidationException("backoffMillis must be >= 0");
            this.backoffMillis = backoffMillis;
            return this;
        }

        /**
         * Per-attempt request timeout in milliseconds, default 30000. A timed-out attempt is retried
         * like a network error; exhausted retries throw {@link CountersTransportException}.
         */
        public Builder requestTimeoutMillis(long requestTimeoutMillis) {
            if (requestTimeoutMillis <= 0) {
                throw new CountersValidationException("requestTimeoutMillis must be > 0");
            }
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
            if (maxBatchSize < 1) throw new CountersValidationException("maxBatchSize must be >= 1");
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
         * off-thread, so without this hook they are silent. Each {@link WriteFailure} carries the
         * coalesced write's identity and an error matchable against the API, transport, and validation
         * subtypes.
         */
        public Builder onBatchError(Consumer<WriteFailure> onBatchError) {
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
