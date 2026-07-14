package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class HttpTest {

    @Test
    void unusableStatusAccessorIsTransport() {
        IllegalStateException cause = new IllegalStateException("no status");
        StubHttpClient client = new StubHttpClient(() -> response(
                () -> { throw cause; },
                () -> "{}",
                HttpTest::emptyHeaders));

        try (CountersClient counters = client(client, 0)) {
            CountersTransportException error = assertThrows(CountersTransportException.class,
                    () -> counters.counter("c").value());
            assertSame(cause, error.getCause());
        }
    }

    @Test
    void impossibleStatusCodeIsTransport() {
        StubHttpClient client = new StubHttpClient(() -> response(
                () -> 0,
                () -> "{}",
                HttpTest::emptyHeaders));

        try (CountersClient counters = client(client, 0)) {
            CountersTransportException error = assertThrows(CountersTransportException.class,
                    () -> counters.counter("c").value());
            assertEquals("transport returned an invalid HTTP status: 0", error.getMessage());
        }
    }

    @Test
    void unavailableBodyAfterARealStatusIsApi() {
        for (int status : new int[] {200, 403}) {
            IllegalStateException cause = new IllegalStateException("no body for " + status);
            StubHttpClient client = new StubHttpClient(() -> response(
                    () -> status,
                    () -> { throw cause; },
                    HttpTest::emptyHeaders));

            try (CountersClient counters = client(client, 0)) {
                CountersApiException error = assertThrows(CountersApiException.class,
                        () -> counters.counter("c").value());
                assertEquals(status, error.status());
                assertSame(cause, error.getCause());
            }
        }
    }

    @Test
    void hostileRetryHeadersFallBackWithoutHidingTheApiStatus() {
        IllegalStateException headerFailure = new IllegalStateException("no headers");
        StubHttpClient client = new StubHttpClient(() -> response(
                () -> 503,
                () -> "{\"title\":\"temporarily unavailable\"}",
                () -> { throw headerFailure; }));

        try (CountersClient counters = client(client, 1)) {
            CountersApiException error = assertThrows(CountersApiException.class,
                    () -> counters.counter("c").value());
            assertEquals(503, error.status());
            assertEquals("temporarily unavailable", error.title());
            assertEquals(2, client.sendCount());
        }
    }

    private static CountersClient client(HttpClient client, int maxRetries) {
        return CountersClient.builder()
                .apiKey("key")
                .baseUrl("https://example.test/v1")
                .httpClient(client)
                .maxRetries(maxRetries)
                .backoffMillis(0)
                .build();
    }

    private static HttpHeaders emptyHeaders() {
        return HttpHeaders.of(Map.of(), (name, value) -> true);
    }

    private static HttpResponse<String> response(
            IntSupplier status,
            Supplier<String> body,
            Supplier<HttpHeaders> headers) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status.getAsInt();
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return headers.get();
            }

            @Override
            public String body() {
                return body.get();
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("https://example.test/v1/counters/c/value");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static final class StubHttpClient extends HttpClient {
        private final Supplier<HttpResponse<String>> responses;
        private final AtomicInteger sends = new AtomicInteger();

        StubHttpClient(Supplier<HttpResponse<String>> responses) {
            this.responses = responses;
        }

        int sendCount() {
            return sends.get();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            sends.incrementAndGet();
            return (HttpResponse<T>) responses.get();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
