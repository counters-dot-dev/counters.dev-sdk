package dev.counters.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.LongConsumer;

/**
 * Low-level transport: bearer auth, idempotency header, JSON, and retry-with-exponential-backoff on
 * connect errors and 429/500/502/503/504. Terminal non-2xx responses throw {@link CountersApiException}
 * with the {@code application/problem+json} title.
 */
final class Http {

    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient client;
    private final int maxRetries;
    private final long backoffMillis;
    private LongConsumer sleeper = Http::sleep; // test seam: records the backoff sequence

    Http(String baseUrl, String apiKey, HttpClient client, int maxRetries, long backoffMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.client = client != null ? client : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.maxRetries = maxRetries;
        this.backoffMillis = backoffMillis;
    }

    /** Test seam: replace the inter-retry sleep to record the backoff sequence. */
    void setSleeper(LongConsumer sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * Perform a request and return the parsed JSON body ({@code Map}/{@code List}/…), or null for 204 / empty
     * bodies. The same request (including the idempotency key) is replayed on retries, so retried writes
     * de-duplicate server-side.
     */
    Object request(String method, String path, Object body, String idempotencyKey, Map<String, String> query) {
        StringBuilder url = new StringBuilder(baseUrl).append(path);
        if (query != null && !query.isEmpty()) {
            StringJoiner qs = new StringJoiner("&");
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e.getValue() != null) qs.add(encode(e.getKey()) + "=" + encode(e.getValue()));
            }
            if (qs.length() > 0) url.append('?').append(qs);
        }

        String payload = body == null ? null : Json.write(body);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey);
        if (payload != null) rb.header("Content-Type", "application/json");
        if (idempotencyKey != null) rb.header("Idempotency-Key", idempotencyKey);
        rb.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        HttpRequest request = rb.build();

        Exception lastErr = null;
        long retryAfterMillis = -1; // -1 => use exponential backoff
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) sleeper.accept(retryAfterMillis >= 0 ? retryAfterMillis : backoffMillis * (1L << (attempt - 1)));
            retryAfterMillis = -1;

            HttpResponse<String> res;
            try {
                res = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                lastErr = e; // connect / network error — retry
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CountersException("request interrupted", e);
            }

            int status = res.statusCode();
            if (status >= 200 && status < 300) {
                String b = res.body();
                if (status == 204 || b == null || b.isEmpty()) return null;
                return Json.parse(b);
            }
            if (RETRYABLE_STATUS.contains(status) && attempt < maxRetries) {
                retryAfterMillis = parseRetryAfter(res.headers().firstValue("retry-after").orElse(null));
                lastErr = new CountersApiException(status, "HTTP " + status);
                continue;
            }
            throw new CountersApiException(status, problemTitle(res.body(), status));
        }
        // B2: retries exhausted with no HTTP response -> transport error (never a status-0 API error).
        throw new CountersTransportException(
                "request failed after " + (maxRetries + 1) + " attempts: " + lastErr, lastErr);
    }

    /** Percent-encode a path segment (valid counter keys are already URL-safe; this is defence in depth). */
    static String encodePathSegment(String s) {
        return encode(s);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String problemTitle(String body, int status) {
        if (body != null && !body.isEmpty()) {
            try {
                if (Json.parse(body) instanceof Map<?, ?> m && m.get("title") instanceof String title) {
                    return title;
                }
            } catch (RuntimeException ignored) {
                // tolerate non-JSON error bodies
            }
        }
        return "HTTP " + status;
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CountersException("retry backoff interrupted", e);
        }
    }

    /** Retry-After as a non-negative integer number of seconds → millis, or -1 (use exponential backoff). */
    static long parseRetryAfter(String value) {
        if (value == null) return -1;
        try {
            long secs = Long.parseLong(value.trim());
            return secs >= 0 ? secs * 1000 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
