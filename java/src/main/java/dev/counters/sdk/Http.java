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

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient client;
    private final int maxRetries;
    private final long backoffMillis;
    private final Duration requestTimeout;
    private LongConsumer sleeper = Http::sleep; // test seam: records the backoff sequence

    Http(String baseUrl, String apiKey, HttpClient client, int maxRetries, long backoffMillis,
            long requestTimeoutMillis) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.client = client != null ? client : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.maxRetries = maxRetries;
        this.backoffMillis = backoffMillis;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
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

        HttpRequest request;
        try {
            String payload = body == null ? null : Json.write(body);
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey);
            if (payload != null) rb.header("Content-Type", "application/json");
            if (idempotencyKey != null) rb.header("Idempotency-Key", idempotencyKey);
            rb.method(method, payload == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            request = rb.build();
        } catch (CountersException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CountersValidationException("invalid HTTP request configuration", e);
        }

        CountersApiException lastApiError = null;
        Exception lastTransportError = null;
        long retryAfterMillis = -1; // -1 => use exponential backoff
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                try {
                    sleeper.accept(retryAfterMillis >= 0
                            ? retryAfterMillis
                            : backoffMillis * (1L << (attempt - 1)));
                } catch (RuntimeException e) {
                    if (lastApiError != null) throw lastApiError;
                    if (e instanceof CountersTransportException transport) throw transport;
                    throw new CountersTransportException("retry backoff failed before a response", e);
                }
            }
            retryAfterMillis = -1;

            HttpResponse<String> res;
            try {
                res = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                lastTransportError = e; // connect / network error — retry
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (lastApiError != null) throw lastApiError;
                throw new CountersTransportException("request interrupted before a response", e);
            } catch (RuntimeException e) {
                if (lastApiError != null) throw lastApiError;
                throw new CountersTransportException("transport failed before a response", e);
            }

            if (res == null) {
                if (lastApiError != null) throw lastApiError;
                throw new CountersTransportException(
                        "transport returned no response", new NullPointerException("HttpClient.send returned null"));
            }

            int status;
            try {
                status = res.statusCode();
            } catch (RuntimeException e) {
                if (lastApiError != null) throw lastApiError;
                throw new CountersTransportException(
                        "transport returned an unusable response before a valid HTTP status", e);
            }
            if (status < 100 || status > 599) {
                if (lastApiError != null) throw lastApiError;
                throw new CountersTransportException(
                        "transport returned an invalid HTTP status: " + status,
                        new IllegalStateException("HTTP status is outside 100..599: " + status));
            }
            if (status >= 200 && status < 300) {
                if (status == 204) return null;
                String b;
                try {
                    b = res.body();
                } catch (RuntimeException e) {
                    throw new CountersApiException(status, "response body is unavailable", e);
                }
                if (b == null || b.isEmpty()) return null;
                try {
                    return Json.parse(b);
                } catch (RuntimeException e) {
                    throw new CountersApiException(status, "response body is not valid JSON", e);
                }
            }
            if (RETRYABLE_STATUS.contains(status) && attempt < maxRetries) {
                retryAfterMillis = retryAfterMillis(res);
                lastApiError = apiError(res, status);
                continue;
            }
            throw apiError(res, status);
        }
        // If any attempt received an HTTP error, preserve that real response classification. Otherwise
        // every attempt failed before a response and this is transport.
        if (lastApiError != null) throw lastApiError;
        throw new CountersTransportException(
                "request failed after " + (maxRetries + 1) + " attempts: " + lastTransportError,
                lastTransportError);
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

    private static CountersApiException apiError(HttpResponse<String> response, int status) {
        try {
            return new CountersApiException(status, problemTitle(response.body(), status));
        } catch (RuntimeException e) {
            return new CountersApiException(status, "response body is unavailable", e);
        }
    }

    private static long retryAfterMillis(HttpResponse<String> response) {
        try {
            var headers = response.headers();
            if (headers == null) return -1;
            return parseRetryAfter(headers.firstValue("retry-after").orElse(null));
        } catch (RuntimeException e) {
            // Retry-After is an optional hint. A hostile custom response must not hide the valid status.
            return -1;
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CountersTransportException("retry backoff interrupted before the next response", e);
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
