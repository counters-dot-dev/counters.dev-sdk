package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ValueFormatTest {

    /** Walk up until the shared format vectors are found, regardless of Gradle's working directory. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> loadVectors() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("format").resolve("vectors.json");
            if (Files.isRegularFile(candidate)) {
                return (Map<String, Object>) Json.parse(Files.readString(candidate));
            }
        }
        throw new IllegalStateException(
                "format/vectors.json not found above " + Paths.get("").toAbsolutePath());
    }

    @TestFactory
    Stream<DynamicTest> formatterVectors() throws IOException {
        List<?> cases = (List<?>) loadVectors().get("cases");
        return cases.stream()
                .map(item -> (Map<?, ?>) item)
                .map(formatCase -> {
                    String id = (String) formatCase.get("id");
                    return dynamicTest(
                            id,
                            () ->
                                    assertEquals(
                                            formatCase.get("expected"),
                                            dispatch(formatCase),
                                            id));
                });
    }

    @Test
    void rejectsInvalidValuesWithSdkValidationException() {
        for (String value : List.of("", "-", "1.5", "+5", " 5", "1_000", "abc")) {
            assertThrows(
                    CountersValidationException.class,
                    () -> ValueFormat.formatCompact(value),
                    "compact: " + value);
            assertThrows(
                    CountersValidationException.class,
                    () -> ValueFormat.formatScientific(value),
                    "scientific: " + value);
            assertThrows(
                    CountersValidationException.class,
                    () -> ValueFormat.formatFull(value),
                    "full: " + value);
            assertThrows(
                    CountersValidationException.class,
                    () -> ValueFormat.describeMagnitude(value),
                    "magnitude: " + value);
        }

        assertThrows(
                CountersValidationException.class,
                () -> ValueFormat.formatCompact((String) null));
        assertThrows(
                CountersValidationException.class,
                () -> ValueFormat.formatCompact((BigInteger) null));
    }

    @Test
    void rejectsOutOfRangeOptionsBeforeFormatting() {
        ValueFormat.FormatOptions belowRange = new ValueFormat.FormatOptions(-1);
        ValueFormat.FormatOptions aboveRange = new ValueFormat.FormatOptions(21);

        assertThrows(
                CountersValidationException.class,
                () -> ValueFormat.formatCompact("1", belowRange));
        assertThrows(
                CountersValidationException.class,
                () -> ValueFormat.formatScientific("1", aboveRange));
        assertThrows(
                CountersValidationException.class,
                () -> ValueFormat.describeMagnitude("1", aboveRange));
    }

    @Test
    void convenienceAndBigIntegerOverloadsUseDefaults() {
        BigInteger value = new BigInteger("1234000000000000000");

        assertEquals("1.234E", ValueFormat.formatCompact(value));
        assertEquals("1.234e18", ValueFormat.formatScientific(value));
        assertEquals("1,234,000,000,000,000,000", ValueFormat.formatFull(value));
        assertEquals("1.234 Quintillion", ValueFormat.describeMagnitude(value));
        assertEquals("1.234E", ValueFormat.formatCompact(value, new ValueFormat.FormatOptions(null)));
    }

    private static String dispatch(Map<?, ?> formatCase) {
        String mode = (String) formatCase.get("mode");
        String value = (String) formatCase.get("value");
        ValueFormat.FormatOptions options = options(formatCase.get("options"));

        return switch (mode) {
            case "compact" -> ValueFormat.formatCompact(value, options);
            case "scientific" -> ValueFormat.formatScientific(value, options);
            case "full" -> ValueFormat.formatFull(value);
            case "magnitude" -> ValueFormat.describeMagnitude(value, options);
            default -> throw new AssertionError("unknown formatter mode: " + mode);
        };
    }

    private static ValueFormat.FormatOptions options(Object value) {
        if (value == null) {
            return null;
        }
        Map<?, ?> options = (Map<?, ?>) value;
        return new ValueFormat.FormatOptions(
                ((Number) options.get("maxFractionDigits")).intValue());
    }
}
