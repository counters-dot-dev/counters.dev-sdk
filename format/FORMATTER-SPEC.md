# Arbitrary-precision display formatter

This document specifies the local display formatter shared by the TypeScript, Go, and Java SDKs.
Given the same value, mode, and options, all three implementations MUST return byte-identical
output.

The formatter accepts signed integer counter values only. Formatting decimal values, including
derived-counter results with a fractional part, is out of scope for v1. The formatter is a local
display helper. It maps to no API operation and does not change the counters.dev wire contract.

[`vectors.json`](./vectors.json) is the byte-exact executable oracle for this specification. Every
implementation MUST dispatch every vector by `mode`, pass its `value` and optional `options`, and
compare the returned string with `expected` for exact equality.

The vector file contains a top-level `description` string and a `cases` array. Every case has a
stable, globally unique `id`, a `mode` from `compact | scientific | full | magnitude`, a signed
digit-string `value`, and the exact output in `expected`. `options` is present only when the case
sets `maxFractionDigits`; its absence selects the mode default. A `full` case never has `options`.

## Input and options

The logical input is either:

- a wire string matching `^-?[0-9]+$`; or
- a language-native arbitrary-precision integer where the public API exposes that input type.

The concrete v1 APIs below expose `string | bigint` in TypeScript and a decimal string in Go and
Java. Go and Java convert that string to their native arbitrary-precision representation before
doing arithmetic.

For a string input, an optional leading `-` is followed by one or more ASCII digits. Leading zeros
are accepted and removed. If all digits are zero, the normalised value is `0`, so inputs such as
`"000"` and `"-0"` both format as `0`.

Any other string is invalid. This includes `""`, `"-"`, `"+5"`, `"1.5"`, `" 5"`, `"1_000"`, and
`"abc"`. An invalid value MUST produce the SDK's validation error: the existing local,
pre-network error role for invalid caller input. It MUST NOT be parsed as a float, rounded, replaced
with a fallback value, or sent over the network.

`compact`, `scientific`, and `magnitude` each accept one optional options object:

```text
maxFractionDigits: integer from 0 through 20, inclusive
```

If the options object or `maxFractionDigits` is omitted, `maxFractionDigits` defaults to `3`. A
present `maxFractionDigits` that is not an integer, or is outside `0..20`, MUST produce the SDK's
validation error. Options are validated before formatting even when the value would not display a
fraction. `full` accepts no options in v1.

## Rules shared by all modes

Zero renders as `0`.

For a negative value, format its absolute value using the selected mode and then prefix exactly one
ASCII hyphen (`-`). Rounding the absolute value before adding the sign makes `HALF_UP` ties round
away from zero for negative inputs.

All output characters are fixed by this document: ASCII digits and punctuation, the listed ASCII
suffixes or words, and lowercase `e` in scientific notation. Implementations MUST NOT use locale
data, non-ASCII minus signs, non-breaking spaces, or environment-specific number formatting.

### Exact `HALF_UP` procedure

The following integer procedure is normative for every rounded quotient. It avoids differences
between decimal libraries:

1. Let `A` be the absolute integer value, `D` the positive power-of-ten divisor for the selected
   ladder step, and `f` the validated `maxFractionDigits`.
2. Let `S = 10^f`.
3. Compute integer quotient and remainder
   `q = floor((A * S) / D)` and `r = (A * S) mod D`.
4. If `2 * r >= D`, increment `q` by one. Otherwise leave `q` unchanged.
5. Render `q / S` with exactly `f` decimal places, then remove trailing fractional zeros and remove
   the decimal point if no fractional digits remain. When `f` is zero, render `q` as an integer.

The multiplication, division, remainder comparison, increment, and rendering MUST be exact. Step 4
defines `HALF_UP`, including an exact half.

## `compact`

`compact` uses this suffix ladder:

| Power | Suffix |
|---:|:---|
| `10^3` | `K` |
| `10^6` | `M` |
| `10^9` | `B` |
| `10^12` | `T` |
| `10^15` | `P` |
| `10^18` | `E` |
| `10^21` | `Z` |
| `10^24` | `Y` |
| `10^27` | `R` |
| `10^30` | `Q` |

If the absolute value is less than `10^3`, return the normalised integer without a suffix or
grouping.

Otherwise, choose the largest ladder power less than or equal to the absolute value. Round
`absolute value / ladder power` with the exact shared procedure, using at most
`maxFractionDigits` fractional digits, and append the suffix without a space.

If the rounded quotient is `1000` or greater, promote to the next ladder step and render again with
the same precision. For example, `999950` with `maxFractionDigits: 1` first rounds `999.95` to
`1000.0`, then promotes and renders as `1M`.

The `Q` step covers values below `10^33`. If the absolute value is at least `10^33`, use
`scientific` mode with the same `maxFractionDigits`. If rounding a `Q` quotient reaches `1000`,
promotion would leave the ladder, so use `scientific` mode instead.

## `scientific`

Except for the small-value rule below, `scientific` renders a normalised mantissa with exactly one
digit before the decimal point, a lowercase `e`, and a base-ten exponent:

```text
d[.ddd]eN
```

The exponent is plain decimal with no leading zeros and no `+` sign. For a non-zero absolute value,
let `N` be its decimal digit count minus one and use `D = 10^N` in the shared rounding procedure.
The rounded mantissa has at most `maxFractionDigits` fractional digits. For example, `121600`
renders as `1.216e5`.

If rounding makes the mantissa `10`, renormalise it to `1` and increment the exponent by one.
Trailing fractional zeros and a resulting decimal point are removed after rounding.

Zero renders as `0`, not `0e0`. A value whose absolute value is less than `10` renders as the
normalised integer with no exponent part; for example, `5` renders as `5`.

## `full`

`full` renders every digit of the exact normalised integer. Insert an ASCII comma between
base-ten groups of three digits, counting from the right. The leftmost group contains one, two, or
three digits. There is no rounding and no length limit introduced by the formatter.

Examples:

| Input | Output |
|:---|:---|
| `121600` | `121,600` |
| `-1000` | `-1,000` |
| `0` | `0` |
| `999` | `999` |

## `magnitude`

`magnitude` uses the compact quotient rules with a space and a short-scale English magnitude word:

| Power | Word |
|---:|:---|
| `10^3` | `Thousand` |
| `10^6` | `Million` |
| `10^9` | `Billion` |
| `10^12` | `Trillion` |
| `10^15` | `Quadrillion` |
| `10^18` | `Quintillion` |
| `10^21` | `Sextillion` |
| `10^24` | `Septillion` |
| `10^27` | `Octillion` |
| `10^30` | `Nonillion` |
| `10^33` | `Decillion` |

If the absolute value is less than `10^3`, return the normalised integer without a word or
grouping. Otherwise, choose the largest listed power less than or equal to the absolute value.
Round the quotient with the exact shared procedure, trim it as specified, then append one ASCII
space and the word. The default `maxFractionDigits` is `3`.

Carry-over promotion is identical to `compact`: a rounded quotient of `1000` or greater selects the
next word and is rendered again. The `Decillion` step covers values below `10^36`. If the absolute
value is at least `10^36`, or if rounding at `Decillion` would promote beyond the word ladder, use
`scientific` mode with the same `maxFractionDigits`.

For example, `1234000000000000000` renders as `1.234 Quintillion`, and its negative renders as
`-1.234 Quintillion`.

## Public API

Names follow each language's normal conventions. The output contract is identical.

### TypeScript

```ts
interface FormatOptions {
  maxFractionDigits?: number;
}

formatCompact(value: string | bigint, options?: FormatOptions): string
formatScientific(value: string | bigint, options?: FormatOptions): string
formatFull(value: string | bigint): string
describeMagnitude(value: string | bigint, options?: FormatOptions): string
```

Invalid values or options throw the TypeScript SDK's validation error.

### Go

```go
type FormatOptions struct {
    MaxFractionDigits *int
}

func FormatCompact(value string, opts *FormatOptions) (string, error)
func FormatScientific(value string, opts *FormatOptions) (string, error)
func FormatFull(value string) (string, error)
func DescribeMagnitude(value string, opts *FormatOptions) (string, error)
```

A nil options pointer or nil `MaxFractionDigits` uses the default. Invalid values or options return
the Go SDK's validation error. Value arithmetic uses `math/big`.

### Java

```java
public static String formatCompact(String value, FormatOptions opts)
public static String formatScientific(String value, FormatOptions opts)
public static String formatFull(String value)
public static String describeMagnitude(String value, FormatOptions opts)
```

A null options reference or absent `maxFractionDigits` uses the default. Invalid values or options
throw the Java SDK's validation exception. Value arithmetic uses `BigInteger` and, where useful for
exact decimal scaling, `BigDecimal` with `RoundingMode.HALF_UP`.

## Float-free implementation requirement

Counter values may be approximately 131,072 decimal digits long. Every operation on the value path
MUST therefore use the normalised digit string or the language's arbitrary-precision types:
TypeScript `BigInt`, Go `math/big`, and Java `BigInteger` or an exactly constructed `BigDecimal`.

Converting the value, any scaled value, quotient, divisor, or rounding remainder through
JavaScript `Number`, Go `float64`, Java `double`, or a fixed-width integer is a defect. That remains
true when a particular test value happens to fit. `BigDecimal` MUST be constructed from a decimal
string, `BigInteger`, or another exact representation, never from a binary floating-point value.
Fixed-width integers may be used only for bounded metadata such as `maxFractionDigits`, digit
counts, ladder indexes, and exponents; they MUST NOT contain the counter value or an arithmetic
derivative of it.
