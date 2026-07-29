package counters

import (
	"math/big"
	"strconv"
	"strings"
)

// FormatOptions carries the optional settings for FormatCompact, FormatScientific, and
// DescribeMagnitude. A nil *FormatOptions, or a nil MaxFractionDigits field, selects the
// default of 3 fraction digits. FormatFull accepts no options.
type FormatOptions struct {
	// MaxFractionDigits bounds the fractional digits in a rendered quotient: an integer from
	// 0 through 20. Any other value is a validation error, reported even when the value would
	// not display a fraction.
	MaxFractionDigits *int
}

const (
	defaultMaxFractionDigits = 3
	minMaxFractionDigits     = 0
	maxMaxFractionDigits     = 20
)

// formatLadderStep is one power-of-ten divisor and the label appended at that scale.
type formatLadderStep struct {
	divisor *big.Int
	label   string
}

// roundedFormatQuotient is a quotient rendered as scaledValue / scale: exact integer output
// of the shared HALF_UP procedure, with scale always a power of ten.
type roundedFormatQuotient struct {
	scale       *big.Int
	scaledValue *big.Int
}

// normalizedFormatValue is a validated input split into sign and absolute value, with the
// absolute value's canonical decimal digits cached for digit counting and grouping.
type normalizedFormatValue struct {
	absolute *big.Int
	digits   string
	negative bool
}

var (
	formatThousand = big.NewInt(1000)
	formatTen      = big.NewInt(10)
	formatTwo      = big.NewInt(2)

	compactLadder = []formatLadderStep{
		{formatPowerOfTen(3), "K"},
		{formatPowerOfTen(6), "M"},
		{formatPowerOfTen(9), "B"},
		{formatPowerOfTen(12), "T"},
		{formatPowerOfTen(15), "P"},
		{formatPowerOfTen(18), "E"},
		{formatPowerOfTen(21), "Z"},
		{formatPowerOfTen(24), "Y"},
		{formatPowerOfTen(27), "R"},
		{formatPowerOfTen(30), "Q"},
	}

	magnitudeLadder = []formatLadderStep{
		{formatPowerOfTen(3), "Thousand"},
		{formatPowerOfTen(6), "Million"},
		{formatPowerOfTen(9), "Billion"},
		{formatPowerOfTen(12), "Trillion"},
		{formatPowerOfTen(15), "Quadrillion"},
		{formatPowerOfTen(18), "Quintillion"},
		{formatPowerOfTen(21), "Sextillion"},
		{formatPowerOfTen(24), "Septillion"},
		{formatPowerOfTen(27), "Octillion"},
		{formatPowerOfTen(30), "Nonillion"},
		{formatPowerOfTen(33), "Decillion"},
	}

	compactScientificThreshold   = formatPowerOfTen(33)
	magnitudeScientificThreshold = formatPowerOfTen(36)
)

// FormatCompact renders value with an SI-style compact suffix (K, M, B, …). Values at or
// beyond the top of the suffix ladder fall back to scientific notation. All arithmetic is
// exact math/big integer work; the output is byte-identical to the other SDKs.
func FormatCompact(value string, opts *FormatOptions) (string, error) {
	maxFractionDigits, err := validateFormatOptions(opts)
	if err != nil {
		return "", err
	}
	normalized, err := normalizeFormatString(value)
	if err != nil {
		return "", err
	}
	formatted := formatLadderAbsolute(normalized.absolute, maxFractionDigits, compactLadder, "", compactScientificThreshold)
	return applyFormatSign(formatted, normalized.negative), nil
}

// FormatCompactBig is FormatCompact for a *big.Int value.
func FormatCompactBig(value *big.Int, opts *FormatOptions) (string, error) {
	maxFractionDigits, err := validateFormatOptions(opts)
	if err != nil {
		return "", err
	}
	normalized, err := normalizeFormatBig(value)
	if err != nil {
		return "", err
	}
	formatted := formatLadderAbsolute(normalized.absolute, maxFractionDigits, compactLadder, "", compactScientificThreshold)
	return applyFormatSign(formatted, normalized.negative), nil
}

// FormatScientific renders value with one leading mantissa digit, a lowercase e, and a
// base-ten exponent. Values below 10 render as the plain integer; zero renders as "0".
func FormatScientific(value string, opts *FormatOptions) (string, error) {
	maxFractionDigits, err := validateFormatOptions(opts)
	if err != nil {
		return "", err
	}
	normalized, err := normalizeFormatString(value)
	if err != nil {
		return "", err
	}
	formatted := formatScientificAbsolute(normalized.absolute, maxFractionDigits)
	return applyFormatSign(formatted, normalized.negative), nil
}

// FormatScientificBig is FormatScientific for a *big.Int value.
func FormatScientificBig(value *big.Int, opts *FormatOptions) (string, error) {
	maxFractionDigits, err := validateFormatOptions(opts)
	if err != nil {
		return "", err
	}
	normalized, err := normalizeFormatBig(value)
	if err != nil {
		return "", err
	}
	formatted := formatScientificAbsolute(normalized.absolute, maxFractionDigits)
	return applyFormatSign(formatted, normalized.negative), nil
}

// FormatFull renders every exact digit of value, grouped by thousands with ASCII commas.
// There is no rounding and no length limit.
func FormatFull(value string) (string, error) {
	normalized, err := normalizeFormatString(value)
	if err != nil {
		return "", err
	}
	return applyFormatSign(groupFormatDigits(normalized.digits), normalized.negative), nil
}

// FormatFullBig is FormatFull for a *big.Int value.
func FormatFullBig(value *big.Int) (string, error) {
	normalized, err := normalizeFormatBig(value)
	if err != nil {
		return "", err
	}
	return applyFormatSign(groupFormatDigits(normalized.digits), normalized.negative), nil
}

// DescribeMagnitude renders value with a short-scale English magnitude word (Thousand,
// Million, …). Values at or beyond the top of the word ladder fall back to scientific
// notation.
func DescribeMagnitude(value string, opts *FormatOptions) (string, error) {
	maxFractionDigits, err := validateFormatOptions(opts)
	if err != nil {
		return "", err
	}
	normalized, err := normalizeFormatString(value)
	if err != nil {
		return "", err
	}
	formatted := formatLadderAbsolute(normalized.absolute, maxFractionDigits, magnitudeLadder, " ", magnitudeScientificThreshold)
	return applyFormatSign(formatted, normalized.negative), nil
}

// DescribeMagnitudeBig is DescribeMagnitude for a *big.Int value.
func DescribeMagnitudeBig(value *big.Int, opts *FormatOptions) (string, error) {
	maxFractionDigits, err := validateFormatOptions(opts)
	if err != nil {
		return "", err
	}
	normalized, err := normalizeFormatBig(value)
	if err != nil {
		return "", err
	}
	formatted := formatLadderAbsolute(normalized.absolute, maxFractionDigits, magnitudeLadder, " ", magnitudeScientificThreshold)
	return applyFormatSign(formatted, normalized.negative), nil
}

func validateFormatOptions(opts *FormatOptions) (int, error) {
	if opts == nil || opts.MaxFractionDigits == nil {
		return defaultMaxFractionDigits, nil
	}
	maxFractionDigits := *opts.MaxFractionDigits
	if maxFractionDigits < minMaxFractionDigits || maxFractionDigits > maxMaxFractionDigits {
		return 0, &ValidationError{"maxFractionDigits must be an integer from 0 through 20"}
	}
	return maxFractionDigits, nil
}

func normalizeFormatString(value string) (*normalizedFormatValue, error) {
	if !valueRe.MatchString(value) {
		return nil, &ValidationError{"value must be a signed integer: " + value}
	}
	n, ok := new(big.Int).SetString(value, 10)
	if !ok {
		return nil, &ValidationError{"value must be a signed integer: " + value}
	}
	return splitFormatSign(n), nil
}

func normalizeFormatBig(value *big.Int) (*normalizedFormatValue, error) {
	if value == nil {
		return nil, &ValidationError{"value must be non-nil"}
	}
	return splitFormatSign(value), nil
}

// splitFormatSign separates sign from magnitude so rounding happens on the absolute value
// and HALF_UP ties round away from zero for negative inputs.
func splitFormatSign(n *big.Int) *normalizedFormatValue {
	negative := n.Sign() < 0
	absolute := new(big.Int).Abs(n)
	return &normalizedFormatValue{
		absolute: absolute,
		digits:   absolute.Text(10),
		negative: negative,
	}
}

func applyFormatSign(formattedAbsolute string, negative bool) string {
	if negative {
		return "-" + formattedAbsolute
	}
	return formattedAbsolute
}

func formatLadderAbsolute(absolute *big.Int, maxFractionDigits int, ladder []formatLadderStep, separator string, scientificThreshold *big.Int) string {
	if absolute.Cmp(formatThousand) < 0 {
		return absolute.Text(10)
	}
	if absolute.Cmp(scientificThreshold) >= 0 {
		return formatScientificAbsolute(absolute, maxFractionDigits)
	}

	stepIndex := findFormatLadderStep(absolute, ladder)
	step := ladder[stepIndex]
	rounded := roundFormatQuotient(absolute, step.divisor, maxFractionDigits)

	// A quotient that rounds to 1000 belongs to the next ladder step.
	if rounded.scaledValue.Cmp(new(big.Int).Mul(formatThousand, rounded.scale)) >= 0 {
		stepIndex++
		if stepIndex >= len(ladder) {
			return formatScientificAbsolute(absolute, maxFractionDigits)
		}
		step = ladder[stepIndex]
		rounded = roundFormatQuotient(absolute, step.divisor, maxFractionDigits)
	}

	return renderFormatScaledInteger(rounded) + separator + step.label
}

func findFormatLadderStep(absolute *big.Int, ladder []formatLadderStep) int {
	for index := len(ladder) - 1; index >= 0; index-- {
		if absolute.Cmp(ladder[index].divisor) >= 0 {
			return index
		}
	}

	// Callers handle values below the first step before searching the ladder.
	panic("ladder has no matching step")
}

func formatScientificAbsolute(absolute *big.Int, maxFractionDigits int) string {
	if absolute.Cmp(formatTen) < 0 {
		return absolute.Text(10)
	}

	// The exponent is a decimal digit count: bounded metadata, never the value itself.
	exponent := len(absolute.Text(10)) - 1
	divisor := formatPowerOfTen(exponent)
	rounded := roundFormatQuotient(absolute, divisor, maxFractionDigits)

	// Rounding 9.99… to 10 requires a one-place exponent promotion.
	if rounded.scaledValue.Cmp(new(big.Int).Mul(formatTen, rounded.scale)) >= 0 {
		exponent++
		rounded = roundedFormatQuotient{
			scale:       rounded.scale,
			scaledValue: new(big.Int).Set(rounded.scale),
		}
	}

	return renderFormatScaledInteger(rounded) + "e" + strconv.Itoa(exponent)
}

func roundFormatQuotient(absolute, divisor *big.Int, maxFractionDigits int) roundedFormatQuotient {
	scale := formatPowerOfTen(maxFractionDigits)
	scaledDividend := new(big.Int).Mul(absolute, scale)
	scaledValue, remainder := new(big.Int), new(big.Int)
	// Every operand is non-negative, so truncated division is floor division here.
	scaledValue.QuoRem(scaledDividend, divisor, remainder)

	// Exact HALF_UP: an exact half increments the positive scaled quotient.
	if new(big.Int).Mul(formatTwo, remainder).Cmp(divisor) >= 0 {
		scaledValue.Add(scaledValue, big.NewInt(1))
	}

	return roundedFormatQuotient{scale: scale, scaledValue: scaledValue}
}

func renderFormatScaledInteger(rounded roundedFormatQuotient) string {
	fractionDigits := len(rounded.scale.Text(10)) - 1
	if fractionDigits == 0 {
		return rounded.scaledValue.Text(10)
	}

	digits := rounded.scaledValue.Text(10)
	if len(digits) < fractionDigits+1 {
		digits = strings.Repeat("0", fractionDigits+1-len(digits)) + digits
	}
	wholeEnd := len(digits) - fractionDigits
	whole := digits[:wholeEnd]
	fraction := strings.TrimRight(digits[wholeEnd:], "0")
	if fraction == "" {
		return whole
	}
	return whole + "." + fraction
}

func formatPowerOfTen(exponent int) *big.Int {
	return new(big.Int).Exp(formatTen, big.NewInt(int64(exponent)), nil)
}

func groupFormatDigits(digits string) string {
	if len(digits) <= 3 {
		return digits
	}

	firstGroupLength := len(digits) % 3
	if firstGroupLength == 0 {
		firstGroupLength = 3
	}
	groups := []string{digits[:firstGroupLength]}
	for index := firstGroupLength; index < len(digits); index += 3 {
		groups = append(groups, digits[index:index+3])
	}
	return strings.Join(groups, ",")
}
