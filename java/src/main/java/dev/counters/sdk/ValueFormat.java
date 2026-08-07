package dev.counters.sdk;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

/** Local display formatting for arbitrary-precision signed integer counter values. */
public final class ValueFormat {

    /**
     * Options shared by compact, scientific, and magnitude formatting.
     *
     * @param maxFractionDigits maximum fractional digits from 0 through 20, or {@code null} for the
     *     default of 3
     */
    public record FormatOptions(Integer maxFractionDigits) {}

    private record NormalizedValue(BigInteger absolute, String digits, boolean negative) {}

    private record LadderStep(BigInteger divisor, String label) {}

    private static final int DEFAULT_MAX_FRACTION_DIGITS = 3;
    private static final int MIN_MAX_FRACTION_DIGITS = 0;
    private static final int MAX_MAX_FRACTION_DIGITS = 20;
    private static final BigInteger TEN = BigInteger.TEN;
    private static final BigInteger THOUSAND = TEN.pow(3);
    private static final BigDecimal DECIMAL_TEN = new BigDecimal(TEN);
    private static final BigDecimal DECIMAL_THOUSAND = new BigDecimal(THOUSAND);

    private static final List<LadderStep> COMPACT_LADDER =
            List.of(
                    step(3, "K"),
                    step(6, "M"),
                    step(9, "B"),
                    step(12, "T"),
                    step(15, "P"),
                    step(18, "E"),
                    step(21, "Z"),
                    step(24, "Y"),
                    step(27, "R"),
                    step(30, "Q"));

    private static final List<LadderStep> MAGNITUDE_LADDER =
            List.of(
                    step(3, "Thousand"),
                    step(6, "Million"),
                    step(9, "Billion"),
                    step(12, "Trillion"),
                    step(15, "Quadrillion"),
                    step(18, "Quintillion"),
                    step(21, "Sextillion"),
                    step(24, "Septillion"),
                    step(27, "Octillion"),
                    step(30, "Nonillion"),
                    step(33, "Decillion"));

    private static final BigInteger COMPACT_SCIENTIFIC_THRESHOLD = TEN.pow(33);
    private static final BigInteger MAGNITUDE_SCIENTIFIC_THRESHOLD = TEN.pow(36);

    private ValueFormat() {}

    /** Renders an integer with an SI-style compact suffix using the default options. */
    public static String formatCompact(String value) {
        return formatCompact(value, null);
    }

    /** Renders an integer with an SI-style compact suffix. */
    public static String formatCompact(String value, FormatOptions opts) {
        int maxFractionDigits = validateOptions(opts);
        return formatCompact(normalizeValue(value), maxFractionDigits);
    }

    /** Renders an arbitrary-precision integer with an SI-style compact suffix using defaults. */
    public static String formatCompact(BigInteger value) {
        return formatCompact(value, null);
    }

    /** Renders an arbitrary-precision integer with an SI-style compact suffix. */
    public static String formatCompact(BigInteger value, FormatOptions opts) {
        int maxFractionDigits = validateOptions(opts);
        return formatCompact(normalizeValue(value), maxFractionDigits);
    }

    /** Renders an integer with one leading mantissa digit using the default options. */
    public static String formatScientific(String value) {
        return formatScientific(value, null);
    }

    /** Renders an integer with one leading mantissa digit and a base-ten exponent. */
    public static String formatScientific(String value, FormatOptions opts) {
        int maxFractionDigits = validateOptions(opts);
        return formatScientific(normalizeValue(value), maxFractionDigits);
    }

    /** Renders an arbitrary-precision integer in scientific notation using default options. */
    public static String formatScientific(BigInteger value) {
        return formatScientific(value, null);
    }

    /** Renders an arbitrary-precision integer with one leading mantissa digit. */
    public static String formatScientific(BigInteger value, FormatOptions opts) {
        int maxFractionDigits = validateOptions(opts);
        return formatScientific(normalizeValue(value), maxFractionDigits);
    }

    /** Renders every exact digit, grouped by thousands with ASCII commas. */
    public static String formatFull(String value) {
        return formatFull(normalizeValue(value));
    }

    /** Renders every digit of an arbitrary-precision integer, grouped with ASCII commas. */
    public static String formatFull(BigInteger value) {
        return formatFull(normalizeValue(value));
    }

    /** Renders an integer with a short-scale English magnitude word using default options. */
    public static String describeMagnitude(String value) {
        return describeMagnitude(value, null);
    }

    /** Renders an integer with a short-scale English magnitude word. */
    public static String describeMagnitude(String value, FormatOptions opts) {
        int maxFractionDigits = validateOptions(opts);
        return describeMagnitude(normalizeValue(value), maxFractionDigits);
    }

    /** Renders an arbitrary-precision integer with a magnitude word using default options. */
    public static String describeMagnitude(BigInteger value) {
        return describeMagnitude(value, null);
    }

    /** Renders an arbitrary-precision integer with a short-scale English magnitude word. */
    public static String describeMagnitude(BigInteger value, FormatOptions opts) {
        int maxFractionDigits = validateOptions(opts);
        return describeMagnitude(normalizeValue(value), maxFractionDigits);
    }

    private static String formatCompact(NormalizedValue value, int maxFractionDigits) {
        String formatted =
                formatLadderAbsolute(
                        value.absolute(),
                        maxFractionDigits,
                        COMPACT_LADDER,
                        "",
                        COMPACT_SCIENTIFIC_THRESHOLD);
        return applySign(formatted, value.negative());
    }

    private static String formatScientific(NormalizedValue value, int maxFractionDigits) {
        return applySign(
                formatScientificAbsolute(value.absolute(), maxFractionDigits), value.negative());
    }

    private static String formatFull(NormalizedValue value) {
        return applySign(groupDigits(value.digits()), value.negative());
    }

    private static String describeMagnitude(NormalizedValue value, int maxFractionDigits) {
        String formatted =
                formatLadderAbsolute(
                        value.absolute(),
                        maxFractionDigits,
                        MAGNITUDE_LADDER,
                        " ",
                        MAGNITUDE_SCIENTIFIC_THRESHOLD);
        return applySign(formatted, value.negative());
    }

    private static int validateOptions(FormatOptions opts) {
        if (opts == null || opts.maxFractionDigits() == null) {
            return DEFAULT_MAX_FRACTION_DIGITS;
        }
        int maxFractionDigits = opts.maxFractionDigits();
        if (maxFractionDigits < MIN_MAX_FRACTION_DIGITS
                || maxFractionDigits > MAX_MAX_FRACTION_DIGITS) {
            throw new CountersValidationException(
                    "maxFractionDigits must be an integer from 0 through 20");
        }
        return maxFractionDigits;
    }

    private static NormalizedValue normalizeValue(String value) {
        return normalizeValue(Validation.toValue(value));
    }

    private static NormalizedValue normalizeValue(BigInteger value) {
        BigInteger normalized = Validation.toValue(value);
        boolean negative = normalized.signum() < 0;
        BigInteger absolute = normalized.abs();
        return new NormalizedValue(absolute, absolute.toString(), negative);
    }

    private static String applySign(String formattedAbsolute, boolean negative) {
        return negative ? "-" + formattedAbsolute : formattedAbsolute;
    }

    private static String formatLadderAbsolute(
            BigInteger absolute,
            int maxFractionDigits,
            List<LadderStep> ladder,
            String separator,
            BigInteger scientificThreshold) {
        if (absolute.compareTo(THOUSAND) < 0) {
            return absolute.toString();
        }
        if (absolute.compareTo(scientificThreshold) >= 0) {
            return formatScientificAbsolute(absolute, maxFractionDigits);
        }

        int stepIndex = findLadderStep(absolute, ladder);
        LadderStep step = ladder.get(stepIndex);
        BigDecimal rounded = roundQuotient(absolute, step.divisor(), maxFractionDigits);

        if (rounded.compareTo(DECIMAL_THOUSAND) >= 0) {
            stepIndex += 1;
            if (stepIndex >= ladder.size()) {
                return formatScientificAbsolute(absolute, maxFractionDigits);
            }
            step = ladder.get(stepIndex);
            rounded = roundQuotient(absolute, step.divisor(), maxFractionDigits);
        }

        return renderRounded(rounded) + separator + step.label();
    }

    private static int findLadderStep(BigInteger absolute, List<LadderStep> ladder) {
        for (int index = ladder.size() - 1; index >= 0; index -= 1) {
            if (absolute.compareTo(ladder.get(index).divisor()) >= 0) {
                return index;
            }
        }
        throw new IllegalStateException("ladder has no matching step");
    }

    private static String formatScientificAbsolute(
            BigInteger absolute, int maxFractionDigits) {
        if (absolute.compareTo(TEN) < 0) {
            return absolute.toString();
        }

        int exponent = absolute.toString().length() - 1;
        BigInteger divisor = powerOfTen(exponent);
        BigDecimal rounded = roundQuotient(absolute, divisor, maxFractionDigits);

        if (rounded.compareTo(DECIMAL_TEN) >= 0) {
            exponent += 1;
            rounded = BigDecimal.ONE;
        }

        return renderRounded(rounded) + "e" + exponent;
    }

    private static BigDecimal roundQuotient(
            BigInteger absolute, BigInteger divisor, int maxFractionDigits) {
        return new BigDecimal(absolute)
                .divide(new BigDecimal(divisor), maxFractionDigits, RoundingMode.HALF_UP);
    }

    private static String renderRounded(BigDecimal rounded) {
        return rounded.stripTrailingZeros().toPlainString();
    }

    private static BigInteger powerOfTen(int exponent) {
        return TEN.pow(exponent);
    }

    private static String groupDigits(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }

        int firstGroupLength = digits.length() % 3;
        if (firstGroupLength == 0) {
            firstGroupLength = 3;
        }
        StringBuilder grouped = new StringBuilder(digits.length() + digits.length() / 3);
        grouped.append(digits, 0, firstGroupLength);
        for (int index = firstGroupLength; index < digits.length(); index += 3) {
            grouped.append(',').append(digits, index, index + 3);
        }
        return grouped.toString();
    }

    private static LadderStep step(int exponent, String label) {
        return new LadderStep(powerOfTen(exponent), label);
    }
}
