package counters

import (
	"encoding/json"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"testing"
)

type formatCase struct {
	ID      string `json:"id"`
	Mode    string `json:"mode"`
	Value   string `json:"value"`
	Options *struct {
		MaxFractionDigits *int `json:"maxFractionDigits"`
	} `json:"options"`
	Expected string `json:"expected"`
}

func loadFormatCases(t *testing.T) []formatCase {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("..", "format", "vectors.json"))
	if err != nil {
		t.Fatalf("read format vectors: %v", err)
	}
	var doc struct {
		Description string       `json:"description"`
		Cases       []formatCase `json:"cases"`
	}
	if err := json.Unmarshal(data, &doc); err != nil {
		t.Fatalf("parse format vectors: %v", err)
	}
	if len(doc.Cases) == 0 {
		t.Fatal("format vectors contain zero cases")
	}
	seen := map[string]bool{}
	ids := map[string]bool{}
	for _, c := range doc.Cases {
		seen[c.Mode] = true
		if ids[c.ID] {
			t.Fatalf("duplicate case id %q", c.ID)
		}
		ids[c.ID] = true
	}
	for _, mode := range []string{"compact", "scientific", "full", "magnitude"} {
		if !seen[mode] {
			t.Fatalf("format vectors are missing mode %q", mode)
		}
	}
	return doc.Cases
}

func formatCaseOptions(c formatCase) *FormatOptions {
	if c.Options == nil {
		return nil
	}
	return &FormatOptions{MaxFractionDigits: c.Options.MaxFractionDigits}
}

// TestFormatVectors replays every shared vector byte-for-byte. A mismatch here means the Go
// port diverges from the frozen specification, not that the vector is wrong.
func TestFormatVectors(t *testing.T) {
	for _, c := range loadFormatCases(t) {
		var (
			got string
			err error
		)
		opts := formatCaseOptions(c)
		switch c.Mode {
		case "compact":
			got, err = FormatCompact(c.Value, opts)
		case "scientific":
			got, err = FormatScientific(c.Value, opts)
		case "full":
			got, err = FormatFull(c.Value)
		case "magnitude":
			got, err = DescribeMagnitude(c.Value, opts)
		default:
			t.Errorf("%s: unknown mode %q", c.ID, c.Mode)
			continue
		}
		if err != nil {
			t.Errorf("%s: unexpected error: %v", c.ID, err)
			continue
		}
		if got != c.Expected {
			t.Errorf("%s: mode %s value %s: got %q, want %q", c.ID, c.Mode, c.Value, got, c.Expected)
		}
	}
}

func TestFormatValidationErrors(t *testing.T) {
	badValues := []string{"", "-", "1.5", "+5", " 5", "abc", "1_000", "5 "}
	for _, value := range badValues {
		for name, format := range map[string]func(string) (string, error){
			"FormatCompact":     func(v string) (string, error) { return FormatCompact(v, nil) },
			"FormatScientific":  func(v string) (string, error) { return FormatScientific(v, nil) },
			"FormatFull":        FormatFull,
			"DescribeMagnitude": func(v string) (string, error) { return DescribeMagnitude(v, nil) },
		} {
			if _, err := format(value); err == nil {
				t.Errorf("%s(%q): expected a validation error, got none", name, value)
			} else {
				var verr *ValidationError
				if !errors.As(err, &verr) {
					t.Errorf("%s(%q): expected *ValidationError, got %T: %v", name, value, err, err)
				}
			}
		}
	}
}

func TestFormatOptionsValidation(t *testing.T) {
	outOfRange := []int{-1, 21, 100}
	for _, f := range outOfRange {
		opts := &FormatOptions{MaxFractionDigits: &f}
		// Options are validated before formatting, even when the value would not display a
		// fraction ("0" never does) — and even when the value itself is invalid.
		for _, value := range []string{"0", "5", "abc"} {
			for name, format := range map[string]func(string, *FormatOptions) (string, error){
				"FormatCompact":     FormatCompact,
				"FormatScientific":  FormatScientific,
				"DescribeMagnitude": DescribeMagnitude,
			} {
				if _, err := format(value, opts); err == nil {
					t.Errorf("%s(%q, maxFractionDigits=%d): expected a validation error, got none", name, value, f)
				} else {
					var verr *ValidationError
					if !errors.As(err, &verr) {
						t.Errorf("%s(%q, maxFractionDigits=%d): expected *ValidationError, got %T: %v", name, value, f, err, err)
					} else if verr.Msg != "maxFractionDigits must be an integer from 0 through 20" {
						t.Errorf("%s(%q, maxFractionDigits=%d): expected option validation error, got %q", name, value, f, verr.Msg)
					}
				}
			}
		}
	}

	// Boundary values are accepted, and a nil field behaves like nil options.
	zero, twenty := 0, 20
	for _, opts := range []*FormatOptions{nil, {}, {MaxFractionDigits: &zero}, {MaxFractionDigits: &twenty}} {
		if _, err := FormatCompact("1500", opts); err != nil {
			t.Errorf("FormatCompact with opts %+v: unexpected error: %v", opts, err)
		}
	}
}

func TestFormatBigVariants(t *testing.T) {
	// The *big.Int entry points share the string code path; spot-check them against the
	// string forms, including a negative and a nil-input validation error.
	inputs := []string{"0", "-0", "999", "-1000000", "1234567890123456789012345678901234567890"}
	for _, value := range inputs {
		n, ok := new(big.Int).SetString(value, 10)
		if !ok {
			t.Fatalf("SetString(%q) failed", value)
		}
		pairs := [][2]func() (string, error){
			{func() (string, error) { return FormatCompact(value, nil) }, func() (string, error) { return FormatCompactBig(n, nil) }},
			{func() (string, error) { return FormatScientific(value, nil) }, func() (string, error) { return FormatScientificBig(n, nil) }},
			{func() (string, error) { return FormatFull(value) }, func() (string, error) { return FormatFullBig(n) }},
			{func() (string, error) { return DescribeMagnitude(value, nil) }, func() (string, error) { return DescribeMagnitudeBig(n, nil) }},
		}
		for _, pair := range pairs {
			fromString, err := pair[0]()
			if err != nil {
				t.Fatalf("string form for %q: %v", value, err)
			}
			fromBig, err := pair[1]()
			if err != nil {
				t.Fatalf("big form for %q: %v", value, err)
			}
			if fromString != fromBig {
				t.Errorf("value %s: string form %q != big form %q", value, fromString, fromBig)
			}
		}
	}

	for name, format := range map[string]func(*big.Int) (string, error){
		"FormatCompactBig":     func(v *big.Int) (string, error) { return FormatCompactBig(v, nil) },
		"FormatScientificBig":  func(v *big.Int) (string, error) { return FormatScientificBig(v, nil) },
		"FormatFullBig":        FormatFullBig,
		"DescribeMagnitudeBig": func(v *big.Int) (string, error) { return DescribeMagnitudeBig(v, nil) },
	} {
		if _, err := format(nil); err == nil {
			t.Errorf("%s(nil): expected a validation error, got none", name)
		} else {
			var verr *ValidationError
			if !errors.As(err, &verr) {
				t.Errorf("%s(nil): expected *ValidationError, got %T: %v", name, err, err)
			}
		}
	}
}
