import { CountersValidationError } from "./errors.js";

export interface FormatOptions {
  maxFractionDigits?: number;
}

interface NormalizedValue {
  absolute: bigint;
  digits: string;
  negative: boolean;
}

interface LadderStep {
  divisor: bigint;
  label: string;
}

interface RoundedQuotient {
  scale: bigint;
  scaledValue: bigint;
}

const SIGNED_INTEGER_RE = /^-?[0-9]+$/;
const TRAILING_ZERO_RE = /0+$/;
const DEFAULT_MAX_FRACTION_DIGITS = 3;
const MIN_MAX_FRACTION_DIGITS = 0;
const MAX_MAX_FRACTION_DIGITS = 20;
const THOUSAND = 1000n;

const COMPACT_LADDER: readonly LadderStep[] = [
  { divisor: 10n ** 3n, label: "K" },
  { divisor: 10n ** 6n, label: "M" },
  { divisor: 10n ** 9n, label: "B" },
  { divisor: 10n ** 12n, label: "T" },
  { divisor: 10n ** 15n, label: "P" },
  { divisor: 10n ** 18n, label: "E" },
  { divisor: 10n ** 21n, label: "Z" },
  { divisor: 10n ** 24n, label: "Y" },
  { divisor: 10n ** 27n, label: "R" },
  { divisor: 10n ** 30n, label: "Q" },
];

const MAGNITUDE_LADDER: readonly LadderStep[] = [
  { divisor: 10n ** 3n, label: "Thousand" },
  { divisor: 10n ** 6n, label: "Million" },
  { divisor: 10n ** 9n, label: "Billion" },
  { divisor: 10n ** 12n, label: "Trillion" },
  { divisor: 10n ** 15n, label: "Quadrillion" },
  { divisor: 10n ** 18n, label: "Quintillion" },
  { divisor: 10n ** 21n, label: "Sextillion" },
  { divisor: 10n ** 24n, label: "Septillion" },
  { divisor: 10n ** 27n, label: "Octillion" },
  { divisor: 10n ** 30n, label: "Nonillion" },
  { divisor: 10n ** 33n, label: "Decillion" },
];

const COMPACT_SCIENTIFIC_THRESHOLD = 10n ** 33n;
const MAGNITUDE_SCIENTIFIC_THRESHOLD = 10n ** 36n;

/** Render an integer with an SI-style compact suffix. */
export function formatCompact(value: string | bigint, options?: FormatOptions): string {
  const maxFractionDigits = validateOptions(options);
  const normalized = normalizeValue(value);
  const formatted = formatLadderAbsolute(
    normalized.absolute,
    maxFractionDigits,
    COMPACT_LADDER,
    "",
    COMPACT_SCIENTIFIC_THRESHOLD,
  );
  return applySign(formatted, normalized.negative);
}

/** Render an integer with one leading mantissa digit and a base-ten exponent. */
export function formatScientific(value: string | bigint, options?: FormatOptions): string {
  const maxFractionDigits = validateOptions(options);
  const normalized = normalizeValue(value);
  const formatted = formatScientificAbsolute(normalized.absolute, maxFractionDigits);
  return applySign(formatted, normalized.negative);
}

/** Render every exact digit, grouped by thousands with ASCII commas. */
export function formatFull(value: string | bigint): string {
  const normalized = normalizeValue(value);
  const formatted = groupDigits(normalized.digits);
  return applySign(formatted, normalized.negative);
}

/** Render an integer with a short-scale English magnitude word. */
export function describeMagnitude(value: string | bigint, options?: FormatOptions): string {
  const maxFractionDigits = validateOptions(options);
  const normalized = normalizeValue(value);
  const formatted = formatLadderAbsolute(
    normalized.absolute,
    maxFractionDigits,
    MAGNITUDE_LADDER,
    " ",
    MAGNITUDE_SCIENTIFIC_THRESHOLD,
  );
  return applySign(formatted, normalized.negative);
}

function validateOptions(options: FormatOptions | undefined): number {
  if (options === undefined) {
    return DEFAULT_MAX_FRACTION_DIGITS;
  }
  if (typeof options !== "object" || options === null || Array.isArray(options)) {
    throw new CountersValidationError("format options must be an object");
  }
  if (options.maxFractionDigits === undefined) {
    return DEFAULT_MAX_FRACTION_DIGITS;
  }

  const maxFractionDigits = options.maxFractionDigits;
  if (
    !Number.isInteger(maxFractionDigits) ||
    maxFractionDigits < MIN_MAX_FRACTION_DIGITS ||
    maxFractionDigits > MAX_MAX_FRACTION_DIGITS
  ) {
    throw new CountersValidationError("maxFractionDigits must be an integer from 0 through 20");
  }
  return maxFractionDigits;
}

function normalizeValue(value: string | bigint): NormalizedValue {
  if (typeof value !== "string" && typeof value !== "bigint") {
    throw new CountersValidationError("value must be a bigint or signed integer string");
  }
  if (typeof value === "string" && !SIGNED_INTEGER_RE.test(value)) {
    throw new CountersValidationError("value string must match ^-?[0-9]+$");
  }

  const integer = typeof value === "bigint" ? value : BigInt(value);
  const negative = integer < 0n;
  const absolute = negative ? -integer : integer;
  return {
    absolute,
    digits: absolute.toString(),
    negative,
  };
}

function applySign(formattedAbsolute: string, negative: boolean): string {
  return negative ? `-${formattedAbsolute}` : formattedAbsolute;
}

function formatLadderAbsolute(
  absolute: bigint,
  maxFractionDigits: number,
  ladder: readonly LadderStep[],
  separator: string,
  scientificThreshold: bigint,
): string {
  if (absolute < THOUSAND) {
    return absolute.toString();
  }
  if (absolute >= scientificThreshold) {
    return formatScientificAbsolute(absolute, maxFractionDigits);
  }

  let stepIndex = findLadderStep(absolute, ladder);
  let step = ladder[stepIndex]!;
  let rounded = roundQuotient(absolute, step.divisor, maxFractionDigits);

  // A quotient that rounds to 1000 belongs to the next ladder step.
  if (rounded.scaledValue >= THOUSAND * rounded.scale) {
    stepIndex += 1;
    if (stepIndex >= ladder.length) {
      return formatScientificAbsolute(absolute, maxFractionDigits);
    }
    step = ladder[stepIndex]!;
    rounded = roundQuotient(absolute, step.divisor, maxFractionDigits);
  }

  return `${renderScaledInteger(rounded)}${separator}${step.label}`;
}

function findLadderStep(absolute: bigint, ladder: readonly LadderStep[]): number {
  for (let index = ladder.length - 1; index >= 0; index -= 1) {
    if (absolute >= ladder[index]!.divisor) {
      return index;
    }
  }

  // Callers handle values below the first step before searching the ladder.
  throw new Error("ladder has no matching step");
}

function formatScientificAbsolute(absolute: bigint, maxFractionDigits: number): string {
  if (absolute < 10n) {
    return absolute.toString();
  }

  let exponent = absolute.toString().length - 1;
  const divisor = powerOfTen(exponent);
  let rounded = roundQuotient(absolute, divisor, maxFractionDigits);

  // Rounding 9.99… to 10 requires a one-place exponent promotion.
  if (rounded.scaledValue >= 10n * rounded.scale) {
    exponent += 1;
    rounded = {
      scale: rounded.scale,
      scaledValue: rounded.scale,
    };
  }

  return `${renderScaledInteger(rounded)}e${exponent}`;
}

function roundQuotient(
  absolute: bigint,
  divisor: bigint,
  maxFractionDigits: number,
): RoundedQuotient {
  const scale = powerOfTen(maxFractionDigits);
  const scaledDividend = absolute * scale;
  let scaledValue = scaledDividend / divisor;
  const remainder = scaledDividend % divisor;

  // Exact HALF_UP: an exact half increments the positive scaled quotient.
  if (2n * remainder >= divisor) {
    scaledValue += 1n;
  }

  return { scale, scaledValue };
}

function renderScaledInteger(rounded: RoundedQuotient): string {
  const fractionDigits = rounded.scale.toString().length - 1;
  if (fractionDigits === 0) {
    return rounded.scaledValue.toString();
  }

  const digits = rounded.scaledValue.toString().padStart(fractionDigits + 1, "0");
  const wholeEnd = digits.length - fractionDigits;
  const whole = digits.slice(0, wholeEnd);
  const fraction = digits.slice(wholeEnd).replace(TRAILING_ZERO_RE, "");
  return fraction.length === 0 ? whole : `${whole}.${fraction}`;
}

function powerOfTen(exponent: number): bigint {
  return 10n ** BigInt(exponent);
}

function groupDigits(digits: string): string {
  if (digits.length <= 3) {
    return digits;
  }

  const firstGroupLength = digits.length % 3 || 3;
  const groups = [digits.slice(0, firstGroupLength)];
  for (let index = firstGroupLength; index < digits.length; index += 3) {
    groups.push(digits.slice(index, index + 3));
  }
  return groups.join(",");
}
