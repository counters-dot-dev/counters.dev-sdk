package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Asserts validation and BigInteger arithmetic against the shared vectors in {@code conformance/*.json},
 * so a divergence between server and SDK is caught mechanically (like the Go tests).
 */
class ConformanceTest {

    /** Walk up from the working directory until the conformance dir is found (robust to where Gradle runs). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> loadVectors(String name) throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("conformance").resolve(name);
            if (Files.isRegularFile(candidate)) {
                return (Map<String, Object>) Json.parse(Files.readString(candidate));
            }
        }
        throw new IllegalStateException(
                "conformance/" + name + " not found above " + Paths.get("").toAbsolutePath());
    }

    @Test
    void counterKeyVectors() throws IOException {
        Map<String, Object> v = loadVectors("counter-keys.json");
        for (Object k : (List<?>) v.get("valid")) {
            assertTrue(Validation.isValidCounterKey((String) k), "expected valid key: " + k);
        }
        for (Object k : (List<?>) v.get("invalid")) {
            assertFalse(Validation.isValidCounterKey((String) k), "expected invalid key: " + k);
        }
    }

    @Test
    void memberKeyVectors() throws IOException {
        Map<String, Object> v = loadVectors("member-keys.json");
        for (Object k : (List<?>) v.get("valid")) {
            assertTrue(Validation.isValidMemberKey((String) k), "expected valid member key: " + k);
        }
        for (Object k : (List<?>) v.get("invalid")) {
            assertFalse(Validation.isValidMemberKey((String) k), "expected invalid member key: " + k);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataByteVectors() throws IOException {
        Map<String, Object> v = loadVectors("member-keys.json");
        Map<String, Object> metadata = (Map<String, Object>) v.get("metadata");
        assertEquals(1024L, ((Number) metadata.get("maxBytes")).longValue());
        for (Object value : (List<?>) metadata.get("valid")) {
            assertDoesNotThrow(() -> Validation.assertMetadata((String) value), "expected valid metadata");
        }
        for (Object value : (List<?>) metadata.get("invalid")) {
            assertThrows(CountersValidationException.class,
                    () -> Validation.assertMetadata((String) value), "expected invalid metadata");
        }
    }

    @Test
    void bucketVectors() throws IOException {
        Map<String, Object> v = loadVectors("buckets.json");
        Instant now = Instant.now();
        for (Object b : (List<?>) v.get("valid")) {
            assertTrue(SeriesParams.BUCKETS.contains((String) b), "expected valid bucket: " + b);
            assertDoesNotThrow(() -> new SeriesParams(now, now, (String) b), "expected valid bucket: " + b);
        }
        for (Object b : (List<?>) v.get("invalid")) {
            assertFalse(SeriesParams.BUCKETS.contains((String) b), "expected invalid bucket: " + b);
            assertThrows(CountersValidationException.class,
                    () -> new SeriesParams(now, now, (String) b), "expected invalid bucket: " + b);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void windowVectors() throws IOException {
        Map<String, Object> v = loadVectors("leaderboard/cases.json");
        for (Object item : (List<?>) v.get("encodeQuery")) {
            Map<String, Object> c = (Map<String, Object>) item;
            Map<String, Object> params = (Map<String, Object>) c.get("params");
            if (!params.containsKey("window")) continue;
            String window = (String) params.get("window");
            if (c.containsKey("expect")) {
                assertFalse(Validation.isValidWindow(window), "expected invalid window: " + window);
                assertThrows(CountersValidationException.class,
                        () -> new WindowLeaderboardParams(window), "expected invalid window: " + window);
            } else {
                assertTrue(Validation.isValidWindow(window), "expected valid window: " + window);
                assertDoesNotThrow(() -> new WindowLeaderboardParams(window), "expected valid window: " + window);
            }
        }
    }

    @Test
    void amountVectors() throws IOException {
        Map<String, Object> v = loadVectors("amounts.json");
        for (Object a : (List<?>) v.get("valid")) {
            assertDoesNotThrow(() -> Validation.toAmount((String) a), "expected valid amount: " + a);
        }
        for (Object a : (List<?>) v.get("invalid")) {
            assertThrows(CountersValidationException.class,
                    () -> Validation.toAmount((String) a), "expected invalid amount: " + a);
        }
    }

    @Test
    void bignumAdditionVectors() throws IOException {
        Map<String, Object> v = loadVectors("bignum.json");
        List<?> cases = (List<?>) v.get("addition");
        assertFalse(cases.isEmpty());
        for (Object item : cases) {
            Map<?, ?> c = (Map<?, ?>) item;
            BigInteger a = new BigInteger((String) c.get("a"));
            BigInteger b = new BigInteger((String) c.get("b"));
            assertEquals(c.get("sum"), a.add(b).toString(),
                    () -> c.get("a") + " + " + c.get("b"));
        }
    }

    @Test
    void bignumSubtractionVectors() throws IOException {
        Map<String, Object> v = loadVectors("bignum.json");
        List<?> cases = (List<?>) v.get("subtraction");
        assertFalse(cases.isEmpty());
        for (Object item : cases) {
            Map<?, ?> c = (Map<?, ?>) item;
            BigInteger a = new BigInteger((String) c.get("a"));
            BigInteger b = new BigInteger((String) c.get("b"));
            assertEquals(c.get("diff"), a.subtract(b).toString(),
                    () -> c.get("a") + " - " + c.get("b"));
        }
    }

    @Test
    void toAmountAcceptsAllInputTypes() {
        assertEquals(BigInteger.valueOf(5), Validation.toAmount(5L));
        assertEquals(BigInteger.ZERO, Validation.toAmount(0L));
        assertEquals(BigInteger.valueOf(100), Validation.toAmount("100"));
        assertEquals(BigInteger.valueOf(7), Validation.toAmount(BigInteger.valueOf(7)));
        assertEquals(new BigInteger("100000000000000000000000000000000"),
                Validation.toAmount("100000000000000000000000000000000"));

        assertThrows(CountersValidationException.class, () -> Validation.toAmount(-1L));
        assertThrows(CountersValidationException.class, () -> Validation.toAmount("abc"));
        assertThrows(CountersValidationException.class, () -> Validation.toAmount("-1"));
        assertThrows(CountersValidationException.class, () -> Validation.toAmount("1.5"));
        assertThrows(CountersValidationException.class, () -> Validation.toAmount((String) null));
        assertThrows(CountersValidationException.class, () -> Validation.toAmount(BigInteger.valueOf(-3)));
        assertThrows(CountersValidationException.class, () -> Validation.toAmount((BigInteger) null));
    }

    @Test
    void toValueAcceptsSignedInputs() {
        assertEquals(BigInteger.valueOf(-5), Validation.toValue(-5L));
        assertEquals(new BigInteger("-100000000000000000000000000000000"),
                Validation.toValue("-100000000000000000000000000000000"));
        assertEquals(BigInteger.TEN.negate(), Validation.toValue(BigInteger.TEN.negate()));

        assertThrows(CountersValidationException.class, () -> Validation.toValue("1.5"));
        assertThrows(CountersValidationException.class, () -> Validation.toValue("abc"));
        assertThrows(CountersValidationException.class, () -> Validation.toValue((String) null));
        assertThrows(CountersValidationException.class, () -> Validation.toValue((BigInteger) null));
    }
}
