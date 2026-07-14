package dev.counters.sdk;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Client-side validation. Kept in lockstep with the server + OpenAPI spec and checked against the
 * shared vectors in {@code conformance/*.json}.
 */
public final class Validation {

    /** Allowed counter-key shape. */
    public static final Pattern COUNTER_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,200}$");

    /** Allowed member-key shape. Broader than counter keys: {@code @} and {@code |} are allowed. */
    public static final Pattern MEMBER_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:@|-]{1,256}$");

    /** Maximum UTF-8 byte length for member metadata. */
    public static final int METADATA_MAX_BYTES = 1024;

    /** Allowed bucket sizes (finer buckets may require higher plans server-side). */
    public static final Set<String> BUCKETS = Set.of("1m", "5m", "1h", "1d", "1w", "1mo");

    /** Allowed window sizes for windowed leaderboard reads. */
    public static final Set<String> WINDOWS = Set.of("1h", "6h", "12h", "1d", "7d", "30d");

    /** Leaderboard/member score modes. */
    public static final Set<String> MODES = Set.of("sum", "latest", "min", "max");

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("^[0-9]+$");
    private static final Pattern VALUE_PATTERN = Pattern.compile("^-?[0-9]+$");

    private Validation() {}

    /** Validates a required API key before it is used as an HTTP header value. */
    public static void assertApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new CountersValidationException("CountersClient: apiKey is required");
        }
        assertHeaderValue("apiKey", apiKey);
    }

    /** Validates the configured absolute HTTP(S) API endpoint. */
    public static void assertBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new CountersValidationException("baseUrl is required");
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getRawAuthority() == null) {
                throw new CountersValidationException("baseUrl must be an absolute HTTP(S) URL: " + baseUrl);
            }
        } catch (IllegalArgumentException e) {
            throw new CountersValidationException("invalid baseUrl: " + baseUrl, e);
        }
    }

    /** Validates a caller-supplied idempotency key. */
    public static void assertIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            throw new CountersValidationException("idempotency key must not be empty");
        }
        if (idempotencyKey.length() > 255) {
            throw new CountersValidationException("idempotency key must be at most 255 characters");
        }
        assertHeaderValue("idempotency key", idempotencyKey);
    }

    private static void assertHeaderValue(String name, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0x7f || (c < 0x20 && c != '\t')) {
                throw new CountersValidationException(name + " contains an invalid HTTP header character");
            }
        }
    }

    /** Reports whether {@code key} matches the server's allowed shape. */
    public static boolean isValidCounterKey(String key) {
        return key != null && COUNTER_KEY_PATTERN.matcher(key).matches();
    }

    /** Throws {@link CountersValidationException} if {@code key} is not a valid counter key. */
    public static void assertCounterKey(String key) {
        if (!isValidCounterKey(key)) {
            throw new CountersValidationException("invalid counter key: \"" + key + "\"");
        }
    }

    /** Reports whether {@code member} matches the server's allowed member-key shape. */
    public static boolean isValidMemberKey(String member) {
        return member != null && MEMBER_KEY_PATTERN.matcher(member).matches();
    }

    /** Throws {@link CountersValidationException} if {@code member} is not a valid member key. */
    public static void assertMemberKey(String member) {
        if (!isValidMemberKey(member)) {
            throw new CountersValidationException("invalid member key: \"" + member + "\"");
        }
    }

    /** UTF-8 byte length used for metadata validation. */
    public static int metadataByteLength(String metadata) {
        if (metadata == null) return 0;
        return metadata.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Reports whether {@code metadata} is within the server's byte cap. */
    public static boolean isValidMetadata(String metadata) {
        return metadata != null && metadataByteLength(metadata) <= METADATA_MAX_BYTES;
    }

    /** Throws {@link CountersValidationException} if metadata exceeds 1024 UTF-8 bytes. */
    public static void assertMetadata(String metadata) {
        int bytes = metadataByteLength(metadata);
        if (bytes > METADATA_MAX_BYTES) {
            throw new CountersValidationException(
                    "metadata exceeds " + METADATA_MAX_BYTES + " UTF-8 bytes (got " + bytes + ")");
        }
    }

    /** Reports whether {@code bucket} is one of the fixed series bucket values. */
    public static boolean isValidBucket(String bucket) {
        return bucket != null && BUCKETS.contains(bucket);
    }

    /** Throws {@link CountersValidationException} if {@code bucket} is not valid. */
    public static void assertBucket(String bucket) {
        if (!isValidBucket(bucket)) {
            throw new CountersValidationException(
                    "series: bucket must be one of 1m, 5m, 1h, 1d, 1w, 1mo: \"" + bucket + "\"");
        }
    }

    /** Reports whether {@code window} is one of the fixed windowed-leaderboard values. */
    public static boolean isValidWindow(String window) {
        return window != null && WINDOWS.contains(window);
    }

    /** Throws {@link CountersValidationException} if {@code window} is not valid. */
    public static void assertWindow(String window) {
        if (!isValidWindow(window)) {
            throw new CountersValidationException(
                    "leaderboard window must be one of 1h, 6h, 12h, 1d, 7d, 30d: \"" + window + "\"");
        }
    }

    /** Throws {@link CountersValidationException} if {@code mode}, when present, is not a score mode. */
    public static void assertMode(String mode) {
        if (mode != null && !MODES.contains(mode)) {
            throw new CountersValidationException(
                    "mode must be one of sum, latest, min, max: \"" + mode + "\"");
        }
    }

    /** Normalises a {@code long} amount to a non-negative {@link BigInteger}. */
    public static BigInteger toAmount(long amount) {
        if (amount < 0) {
            throw new CountersValidationException("amount must be non-negative: " + amount);
        }
        return BigInteger.valueOf(amount);
    }

    /**
     * Normalises a decimal-digit string to a non-negative {@link BigInteger}.
     * Strings must match {@code ^[0-9]+$} (conformance/amounts.json) — no sign, spaces, or decimals.
     */
    public static BigInteger toAmount(String amount) {
        if (amount == null || !AMOUNT_PATTERN.matcher(amount).matches()) {
            throw new CountersValidationException("amount must be a non-negative integer: \"" + amount + "\"");
        }
        return new BigInteger(amount);
    }

    /** Validates that a {@link BigInteger} amount is non-negative. */
    public static BigInteger toAmount(BigInteger amount) {
        if (amount == null || amount.signum() < 0) {
            throw new CountersValidationException("amount must be non-negative: " + amount);
        }
        return amount;
    }

    /** Normalises a signed {@code long} value for member score submit. */
    public static BigInteger toValue(long value) {
        return BigInteger.valueOf(value);
    }

    /** Normalises a signed decimal string for member score submit. */
    public static BigInteger toValue(String value) {
        if (value == null || !VALUE_PATTERN.matcher(value).matches()) {
            throw new CountersValidationException("value must be a signed integer: \"" + value + "\"");
        }
        return new BigInteger(value);
    }

    /** Validates a signed {@link BigInteger} value for member score submit. */
    public static BigInteger toValue(BigInteger value) {
        if (value == null) {
            throw new CountersValidationException("value must be non-null");
        }
        return value;
    }
}
