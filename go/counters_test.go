package counters

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func loadVectors(t *testing.T, name string) map[string]any {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("..", "conformance", name))
	if err != nil {
		t.Fatalf("read %s: %v", name, err)
	}
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatalf("parse %s: %v", name, err)
	}
	return m
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func loopbackClient(t *testing.T, f roundTripFunc) *Client {
	t.Helper()
	c, err := NewClient(Options{
		APIKey:  "k",
		BaseURL: "https://unit.test/v1",
		HTTPClient: &http.Client{
			Transport: f,
		},
		MaxRetries: -1, // disable retries: taxonomy cases must observe the first response
	})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func jsonLoopbackResponse(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Header:     http.Header{"Content-Type": []string{"application/json"}},
		Body:       io.NopCloser(strings.NewReader(body)),
	}
}

func jsonString(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func intFromAny(v any) int {
	if v == nil {
		return 0
	}
	return int(v.(float64))
}

func int64PtrFromAny(v any) *int64 {
	if v == nil {
		return nil
	}
	n := int64(v.(float64))
	return &n
}

func seriesModeQueryCaseNames(v map[string]any) []string {
	var names []string
	for _, raw := range v["query"].([]any) {
		c := raw.(map[string]any)
		p := c["params"].(map[string]any)
		if _, hasMode := p["mode"]; hasMode {
			names = append(names, "query/"+c["name"].(string))
		}
	}
	return names
}

func TestCounterKeyConformance(t *testing.T) {
	v := loadVectors(t, "counter-keys.json")
	for _, k := range v["valid"].([]any) {
		if !IsValidCounterKey(k.(string)) {
			t.Errorf("expected valid: %q", k)
		}
	}
	for _, k := range v["invalid"].([]any) {
		if IsValidCounterKey(k.(string)) {
			t.Errorf("expected invalid: %q", k)
		}
	}
}

func TestBucketConformance(t *testing.T) {
	v := loadVectors(t, "buckets.json")
	for _, b := range v["valid"].([]any) {
		if !IsValidBucket(b.(string)) {
			t.Errorf("expected valid bucket: %q", b)
		}
	}
	for _, b := range v["invalid"].([]any) {
		if IsValidBucket(b.(string)) {
			t.Errorf("expected invalid bucket: %q", b)
		}
	}
}

func TestMemberKeyConformance(t *testing.T) {
	v := loadVectors(t, "member-keys.json")
	for _, k := range v["valid"].([]any) {
		if !IsValidMemberKey(k.(string)) {
			t.Errorf("expected valid member key: %q", k)
		}
	}
	for _, k := range v["invalid"].([]any) {
		if IsValidMemberKey(k.(string)) {
			t.Errorf("expected invalid member key: %q", k)
		}
	}
}

func TestMetadataConformance(t *testing.T) {
	v := loadVectors(t, "member-keys.json")
	md := v["metadata"].(map[string]any)
	for _, raw := range md["valid"].([]any) {
		s := raw.(string)
		if !IsValidMetadata(s) {
			t.Errorf("expected valid metadata with %d bytes", MetadataByteLength(s))
		}
		if err := validateMetadata(s); err != nil {
			t.Errorf("validateMetadata valid payload: %v", err)
		}
	}
	for _, raw := range md["invalid"].([]any) {
		s := raw.(string)
		if IsValidMetadata(s) {
			t.Errorf("expected invalid metadata with %d bytes", MetadataByteLength(s))
		}
		if err := validateMetadata(s); err == nil {
			t.Errorf("expected metadata validation error for %d bytes", MetadataByteLength(s))
		}
	}
}

func TestWindowConformance(t *testing.T) {
	for _, w := range []string{"1h", "6h", "12h", "1d", "7d", "30d"} {
		if !IsValidWindow(w) {
			t.Errorf("expected valid window: %q", w)
		}
	}
	for _, w := range []string{"2h", "1m", "1mo", "", "7D", "24h"} {
		if IsValidWindow(w) {
			t.Errorf("expected invalid window: %q", w)
		}
	}
}

func TestSeriesRejectsBadBucket(t *testing.T) {
	c, err := NewClient(Options{APIKey: "k_test"})
	if err != nil {
		t.Fatal(err)
	}
	h, err := c.Counter("signups")
	if err != nil {
		t.Fatal(err)
	}
	_, err = h.Series(context.Background(), SeriesParams{From: time.Now(), To: time.Now(), Bucket: "2m"})
	var ve *ValidationError
	if !errors.As(err, &ve) {
		t.Fatalf("expected *ValidationError for bad bucket, got %v", err)
	}
}

func TestAmountConformance(t *testing.T) {
	v := loadVectors(t, "amounts.json")
	for _, a := range v["valid"].([]any) {
		if _, err := ToAmount(a.(string)); err != nil {
			t.Errorf("expected valid amount %q: %v", a, err)
		}
	}
	for _, a := range v["invalid"].([]any) {
		if _, err := ToAmount(a.(string)); err == nil {
			t.Errorf("expected invalid amount: %q", a)
		}
	}
}

func TestBignumConformance(t *testing.T) {
	v := loadVectors(t, "bignum.json")
	for _, item := range v["addition"].([]any) {
		o := item.(map[string]any)
		a, _ := new(big.Int).SetString(o["a"].(string), 10)
		b, _ := new(big.Int).SetString(o["b"].(string), 10)
		if got := new(big.Int).Add(a, b).String(); got != o["sum"].(string) {
			t.Errorf("%s + %s = %s, want %s", o["a"], o["b"], got, o["sum"])
		}
	}
	for _, item := range v["subtraction"].([]any) {
		o := item.(map[string]any)
		a, _ := new(big.Int).SetString(o["a"].(string), 10)
		b, _ := new(big.Int).SetString(o["b"].(string), 10)
		if got := new(big.Int).Sub(a, b).String(); got != o["diff"].(string) {
			t.Errorf("%s - %s = %s, want %s", o["a"], o["b"], got, o["diff"])
		}
	}
}

func TestToAmountTypes(t *testing.T) {
	for _, ok := range []any{5, int64(42), "100", big.NewInt(7), 0} {
		if _, err := ToAmount(ok); err != nil {
			t.Errorf("ToAmount(%v) unexpected error: %v", ok, err)
		}
	}
	for _, bad := range []any{-1, "abc", "-1", "1.5", big.NewInt(-3)} {
		if _, err := ToAmount(bad); err == nil {
			t.Errorf("ToAmount(%v) expected error", bad)
		}
	}
}

func TestToValueTypes(t *testing.T) {
	for _, ok := range []any{5, int64(-42), "100", "-100", big.NewInt(-7), 0} {
		if _, err := ToValue(ok); err != nil {
			t.Errorf("ToValue(%v) unexpected error: %v", ok, err)
		}
	}
	for _, bad := range []any{"abc", "1.5", "-", "--1"} {
		if _, err := ToValue(bad); err == nil {
			t.Errorf("ToValue(%v) expected error", bad)
		}
	}
}

func TestAddNowAndValue(t *testing.T) {
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/v1/counters/c/add" {
			_ = json.NewEncoder(w).Encode(Counter{Key: "c", Value: "5", Epoch: 0})
		} else {
			_ = json.NewEncoder(w).Encode(ValueResponse{Key: "c", Value: "5", Epoch: 0})
		}
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	h, _ := c.Counter("c")
	ctr, err := h.AddNow(context.Background(), 5)
	if err != nil {
		t.Fatal(err)
	}
	if ctr.Value != "5" {
		t.Errorf("value=%s", ctr.Value)
	}
	if paths[0] != "/v1/counters/c/add" {
		t.Errorf("path=%s", paths[0])
	}
	if v, _ := h.Value(context.Background()); v.Value != "5" {
		t.Errorf("value=%s", v.Value)
	}
}

func TestCounterDecodesCreatedAtUpdatedAt(t *testing.T) {
	c := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
		return jsonLoopbackResponse(200, `{"key":"c","value":"5","epoch":0,"createdAt":"2026-07-01T12:00:00Z","updatedAt":"2026-07-02T08:30:00Z"}`), nil
	})
	h, _ := c.Counter("c")
	ctr, err := h.AddNow(context.Background(), 5)
	if err != nil {
		t.Fatal(err)
	}
	if ctr.CreatedAt == nil || ctr.UpdatedAt == nil {
		t.Fatalf("timestamps not decoded: createdAt=%v updatedAt=%v", ctr.CreatedAt, ctr.UpdatedAt)
	}
	if want := time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC); !ctr.CreatedAt.Equal(want) {
		t.Errorf("createdAt=%v want %v", ctr.CreatedAt, want)
	}
	if want := time.Date(2026, 7, 2, 8, 30, 0, 0, time.UTC); !ctr.UpdatedAt.Equal(want) {
		t.Errorf("updatedAt=%v want %v", ctr.UpdatedAt, want)
	}
}

func TestCounterOmitsTimestampsWhenAbsent(t *testing.T) {
	c := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
		return jsonLoopbackResponse(200, `{"key":"c","value":"5","epoch":0}`), nil
	})
	h, _ := c.Counter("c")
	ctr, err := h.AddNow(context.Background(), 5)
	if err != nil {
		t.Fatal(err)
	}
	if ctr.CreatedAt != nil || ctr.UpdatedAt != nil {
		t.Errorf("expected nil timestamps when the server omits them, got createdAt=%v updatedAt=%v", ctr.CreatedAt, ctr.UpdatedAt)
	}
}

func TestRequiredTimestampsRoundTripAsTime(t *testing.T) {
	const wireTimestamp = "2026-01-01T00:00:00Z"
	want, err := time.Parse(time.RFC3339, wireTimestamp)
	if err != nil {
		t.Fatal(err)
	}

	tests := []struct {
		name string
		body string
		read func([]byte) (time.Time, error)
	}{
		{
			name: "leaderboard entry",
			body: `{"rank":1,"member":"alice","value":"5","updatedAt":"` + wireTimestamp + `"}`,
			read: func(body []byte) (time.Time, error) {
				var entry LeaderboardEntry
				err := json.Unmarshal(body, &entry)
				return entry.UpdatedAt, err
			},
		},
		{
			name: "member snapshot",
			body: `{"key":"lb","member":"alice","value":"5","rank":1,"percentile":"100.00","memberCount":1,"mode":"sum","epoch":0,"updatedAt":"` + wireTimestamp + `"}`,
			read: func(body []byte) (time.Time, error) {
				var snapshot MemberSnapshot
				err := json.Unmarshal(body, &snapshot)
				return snapshot.UpdatedAt, err
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := tt.read([]byte(tt.body))
			if err != nil {
				t.Fatal(err)
			}
			if !got.Equal(want) {
				t.Fatalf("updatedAt=%v, want %v", got, want)
			}
			roundTripped, err := json.Marshal(got)
			if err != nil {
				t.Fatal(err)
			}
			if string(roundTripped) != `"`+wireTimestamp+`"` {
				t.Errorf("round-tripped timestamp=%s, want %q", roundTripped, wireTimestamp)
			}
		})
	}
}

func TestSeriesPointPublicShapeAndWireMapping(t *testing.T) {
	wantTimestamp := time.Date(2026, 7, 1, 12, 30, 0, 0, time.UTC)
	point := SeriesPoint{Timestamp: wantTimestamp, Value: "100000000000000000000000000000000"}

	wire, err := json.Marshal(point)
	if err != nil {
		t.Fatal(err)
	}
	const wantWire = `{"t":"2026-07-01T12:30:00Z","v":"100000000000000000000000000000000"}`
	if string(wire) != wantWire {
		t.Fatalf("series point wire=%s, want %s", wire, wantWire)
	}

	var decoded SeriesPoint
	if err := json.Unmarshal([]byte(wantWire), &decoded); err != nil {
		t.Fatal(err)
	}
	if !decoded.Timestamp.Equal(wantTimestamp) || decoded.Value != point.Value {
		t.Fatalf("decoded series point=%+v, want %+v", decoded, point)
	}

	typ := reflect.TypeOf(SeriesPoint{})
	if typ.NumField() != 2 {
		t.Fatalf("SeriesPoint has %d fields, want exactly Timestamp and Value", typ.NumField())
	}
	timestamp, ok := typ.FieldByName("Timestamp")
	if !ok || timestamp.Type != reflect.TypeOf(time.Time{}) || timestamp.Tag.Get("json") != "t" {
		t.Fatalf("Timestamp field=%+v present=%v", timestamp, ok)
	}
	value, ok := typ.FieldByName("Value")
	if !ok || value.Type.Kind() != reflect.String || value.Tag.Get("json") != "v" {
		t.Fatalf("Value field=%+v present=%v", value, ok)
	}
}

func TestRemainingDateTimeAndWireNamePublicShapes(t *testing.T) {
	timeType := reflect.TypeOf(time.Time{})
	timePointerType := reflect.TypeOf((*time.Time)(nil))
	stringPointerType := reflect.TypeOf((*string)(nil))

	for _, tc := range []struct {
		owner reflect.Type
		field string
		want  reflect.Type
		json  string
	}{
		{reflect.TypeOf(Counter{}), "CreatedAt", timePointerType, "createdAt,omitempty"},
		{reflect.TypeOf(Counter{}), "UpdatedAt", timePointerType, "updatedAt,omitempty"},
		{reflect.TypeOf(SeriesPoint{}), "Timestamp", timeType, "t"},
		{reflect.TypeOf(LeaderboardEntry{}), "UpdatedAt", timeType, "updatedAt"},
		{reflect.TypeOf(MemberSnapshot{}), "UpdatedAt", timeType, "updatedAt"},
		{reflect.TypeOf(WindowLeaderboard{}), "EffectiveStart", timeType, "effectiveStart"},
		{reflect.TypeOf(WindowLeaderboard{}), "EffectiveEnd", timeType, "effectiveEnd"},
		{reflect.TypeOf(MemberWriteOpts{}), "OccurredAt", timePointerType, ""},
		{reflect.TypeOf(SubmitOpts{}), "OccurredAt", timePointerType, ""},
		{reflect.TypeOf(operation{}), "OccurredAt", timePointerType, "occurredAt,omitempty"},
		{reflect.TypeOf(DerivedSeriesPoint{}), "Timestamp", timeType, "t"},
		{reflect.TypeOf(DerivedSeriesPoint{}), "Value", stringPointerType, "v"},
		{reflect.TypeOf(SeriesParams{}), "From", timeType, ""},
		{reflect.TypeOf(SeriesParams{}), "To", timeType, ""},
		{reflect.TypeOf(DerivedSeriesParams{}), "From", timeType, ""},
		{reflect.TypeOf(DerivedSeriesParams{}), "To", timeType, ""},
	} {
		assertStructField(t, tc.owner, tc.field, tc.want, tc.json)
	}

	usageType := reflect.TypeOf(Usage{})
	operations, ok := usageType.FieldByName("Operations")
	if !ok || operations.Tag.Get("json") != "ops" {
		t.Fatalf("Usage.Operations=%+v present=%v", operations, ok)
	}
	assertStructField(t, operations.Type, "ResetsAt", timeType, "resetsAt")
	limits, ok := usageType.FieldByName("Limits")
	if !ok {
		t.Fatal("Usage.Limits is absent")
	}
	assertStructField(t, limits.Type, "RateLimitRequestsPerSecond", reflect.TypeOf(int64(0)), "rateLimitRps")
	assertStructField(t, limits.Type, "MonthlyOperationsQuota", reflect.TypeOf((*int64)(nil)), "monthlyOpsQuota")

	for _, owner := range []reflect.Type{
		reflect.TypeOf(SeriesResponse{}),
		reflect.TypeOf(MemberSeriesResponse{}),
		reflect.TypeOf(MemberGroupSeriesResponse{}),
		reflect.TypeOf(DerivedSeriesResponse{}),
	} {
		rangeField, ok := owner.FieldByName("Range")
		if !ok {
			t.Fatalf("%s.Range is absent", owner.Name())
		}
		assertStructField(t, rangeField.Type, "From", timeType, "from")
		assertStructField(t, rangeField.Type, "To", timeType, "to")
		assertStructField(t, owner, "TimeZone", reflect.TypeOf(""), "tz")
	}
	assertStructField(t, reflect.TypeOf(SeriesParams{}), "TimeZone", reflect.TypeOf(""), "")
	assertStructField(t, reflect.TypeOf(DerivedSeriesParams{}), "TimeZone", reflect.TypeOf(""), "")
	assertStructField(t, reflect.TypeOf(operation{}), "Operation", reflect.TypeOf(""), "op")

	// The spec requires a point value-type `mode` on both member-dimensional series shapes, and the
	// window total is optional (absent on score boards) — so it must be a pointer.
	assertStructField(t, reflect.TypeOf(MemberSeriesResponse{}), "Mode", reflect.TypeOf(""), "mode")
	assertStructField(t, reflect.TypeOf(MemberGroupSeriesResponse{}), "Mode", reflect.TypeOf(""), "mode")
	assertStructField(t, reflect.TypeOf(WindowLeaderboard{}), "Total", stringPointerType, "total,omitempty")

	for _, old := range []struct {
		owner reflect.Type
		field string
	}{
		{reflect.TypeOf(DerivedSeriesPoint{}), "T"},
		{reflect.TypeOf(DerivedSeriesPoint{}), "V"},
		{reflect.TypeOf(SeriesParams{}), "Tz"},
		{reflect.TypeOf(DerivedSeriesParams{}), "Tz"},
		{reflect.TypeOf(operation{}), "Op"},
		{usageType, "Ops"},
		{limits.Type, "RateLimitRps"},
		{limits.Type, "MonthlyOpsQuota"},
	} {
		if _, ok := old.owner.FieldByName(old.field); ok {
			t.Errorf("obsolete shorthand %s.%s remains public", old.owner.Name(), old.field)
		}
	}
}

func assertStructField(t *testing.T, owner reflect.Type, name string, wantType reflect.Type, wantJSON string) {
	t.Helper()
	field, ok := owner.FieldByName(name)
	if !ok {
		t.Fatalf("%s.%s is absent", owner.Name(), name)
	}
	if field.Type != wantType || field.Tag.Get("json") != wantJSON {
		t.Errorf("%s.%s type/tag=%v %q, want %v %q", owner.Name(), name, field.Type, field.Tag.Get("json"), wantType, wantJSON)
	}
}

func TestOptionalRequestDateTimesStayNilWhenAbsent(t *testing.T) {
	if (MemberWriteOpts{}).OccurredAt != nil || (SubmitOpts{}).OccurredAt != nil {
		t.Fatal("absent member request timestamps must remain nil")
	}

	var clearOp operation
	if err := json.Unmarshal([]byte(`{"counterKey":"c","op":"clear"}`), &clearOp); err != nil {
		t.Fatal(err)
	}
	if clearOp.OccurredAt != nil {
		t.Fatalf("absent operation.OccurredAt=%v, want nil", clearOp.OccurredAt)
	}
	wire, err := json.Marshal(clearOp)
	if err != nil {
		t.Fatal(err)
	}
	if string(wire) != `{"counterKey":"c","op":"clear"}` {
		t.Fatalf("operation wire=%s", wire)
	}

	at := time.Date(2026, 7, 1, 12, 30, 0, 0, time.UTC)
	wire, err = json.Marshal(operation{CounterKey: "c", Operation: "add", OccurredAt: &at})
	if err != nil {
		t.Fatal(err)
	}
	if string(wire) != `{"counterKey":"c","op":"add","occurredAt":"2026-07-01T12:30:00Z"}` {
		t.Fatalf("timestamped operation wire=%s", wire)
	}
}

func TestResponseDateTimesDecodeAcrossAllPublicTypes(t *testing.T) {
	const fromWire = "2026-07-01T12:30:00Z"
	const toWire = "2026-07-02T08:45:00Z"
	wantFrom, _ := time.Parse(time.RFC3339, fromWire)
	wantTo, _ := time.Parse(time.RFC3339, toWire)
	rangeBody := `{"range":{"from":"` + fromWire + `","to":"` + toWire + `"}}`

	for _, tc := range []struct {
		name string
		body string
		read func([]byte) (time.Time, time.Time, error)
	}{
		{
			name: "counter series range",
			body: rangeBody,
			read: func(body []byte) (time.Time, time.Time, error) {
				var response SeriesResponse
				err := json.Unmarshal(body, &response)
				return response.Range.From, response.Range.To, err
			},
		},
		{
			name: "member series range",
			body: rangeBody,
			read: func(body []byte) (time.Time, time.Time, error) {
				var response MemberSeriesResponse
				err := json.Unmarshal(body, &response)
				return response.Range.From, response.Range.To, err
			},
		},
		{
			name: "member group series range",
			body: rangeBody,
			read: func(body []byte) (time.Time, time.Time, error) {
				var response MemberGroupSeriesResponse
				err := json.Unmarshal(body, &response)
				return response.Range.From, response.Range.To, err
			},
		},
		{
			name: "derived series range",
			body: rangeBody,
			read: func(body []byte) (time.Time, time.Time, error) {
				var response DerivedSeriesResponse
				err := json.Unmarshal(body, &response)
				return response.Range.From, response.Range.To, err
			},
		},
		{
			name: "window leaderboard bounds",
			body: `{"effectiveStart":"` + fromWire + `","effectiveEnd":"` + toWire + `"}`,
			read: func(body []byte) (time.Time, time.Time, error) {
				var response WindowLeaderboard
				err := json.Unmarshal(body, &response)
				return response.EffectiveStart, response.EffectiveEnd, err
			},
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			gotFrom, gotTo, err := tc.read([]byte(tc.body))
			if err != nil {
				t.Fatal(err)
			}
			if !gotFrom.Equal(wantFrom) || !gotTo.Equal(wantTo) {
				t.Fatalf("date-times=%v..%v, want %v..%v", gotFrom, gotTo, wantFrom, wantTo)
			}
		})
	}

	var usage Usage
	if err := json.Unmarshal([]byte(`{"ops":{"resetsAt":"`+toWire+`"}}`), &usage); err != nil {
		t.Fatal(err)
	}
	if !usage.Operations.ResetsAt.Equal(wantTo) {
		t.Fatalf("usage reset=%v, want %v", usage.Operations.ResetsAt, wantTo)
	}
}

func TestDerivedSeriesPointPreservesNullValueOnErgonomicFields(t *testing.T) {
	const wire = `{"t":"2026-07-01T12:30:00Z","v":null}`
	var point DerivedSeriesPoint
	if err := json.Unmarshal([]byte(wire), &point); err != nil {
		t.Fatal(err)
	}
	wantTimestamp := time.Date(2026, 7, 1, 12, 30, 0, 0, time.UTC)
	if !point.Timestamp.Equal(wantTimestamp) || point.Value != nil {
		t.Fatalf("derived point=%+v, want timestamp %v and nil value", point, wantTimestamp)
	}
	roundTripped, err := json.Marshal(point)
	if err != nil {
		t.Fatal(err)
	}
	if string(roundTripped) != wire {
		t.Fatalf("derived point wire=%s, want %s", roundTripped, wire)
	}
}

func TestPublishablePublicMethodSets(t *testing.T) {
	tests := []struct {
		name string
		typ  reflect.Type
		want []string
	}{
		{"PublishableClient", reflect.TypeOf((*PublishableClient)(nil)), []string{"Close", "Counter"}},
		{"PublishableCounterHandle", reflect.TypeOf((*PublishableCounterHandle)(nil)), []string{
			"GroupSeries", "Leaderboard", "Member", "MemberSeries", "Series", "Value", "WindowLeaderboard",
		}},
		{"PublishableMemberHandle", reflect.TypeOf((*PublishableMemberHandle)(nil)), []string{"Get"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := make([]string, tt.typ.NumMethod())
			for i := 0; i < tt.typ.NumMethod(); i++ {
				got[i] = tt.typ.Method(i).Name
			}
			if !slices.Equal(got, tt.want) {
				t.Fatalf("exported methods=%v, want exactly %v", got, tt.want)
			}
		})
	}
}

func TestPublishableHandleIdentityFields(t *testing.T) {
	client, err := NewPublishableClient(PublishableOptions{APIKey: "pk_test"})
	if err != nil {
		t.Fatal(err)
	}
	counter, err := client.Counter("visible")
	if err != nil {
		t.Fatal(err)
	}
	if counter.Key != "visible" {
		t.Fatalf("counter Key=%q, want visible", counter.Key)
	}
	member, err := counter.Member("alice")
	if err != nil {
		t.Fatal(err)
	}
	if member.CounterKey != "visible" || member.Member != "alice" {
		t.Fatalf("member identity=(%q, %q), want (visible, alice)", member.CounterKey, member.Member)
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}

	for _, tt := range []struct {
		name string
		typ  reflect.Type
		want []string
	}{
		{"PublishableCounterHandle", reflect.TypeOf(PublishableCounterHandle{}), []string{"Key"}},
		{"PublishableMemberHandle", reflect.TypeOf(PublishableMemberHandle{}), []string{"CounterKey", "Member"}},
	} {
		var got []string
		for i := 0; i < tt.typ.NumField(); i++ {
			field := tt.typ.Field(i)
			if field.IsExported() {
				got = append(got, field.Name)
				if field.Type.Kind() != reflect.String {
					t.Fatalf("%s.%s type=%v, want string", tt.name, field.Name, field.Type)
				}
			}
		}
		if !slices.Equal(got, tt.want) {
			t.Fatalf("%s exported fields=%v, want exactly %v", tt.name, got, tt.want)
		}
	}
}

func TestBatchOnErrorPublicShapeIsTyped(t *testing.T) {
	field, ok := reflect.TypeOf(BatchOptions{}).FieldByName("OnError")
	if !ok {
		t.Fatal("BatchOptions.OnError is missing")
	}
	want := reflect.TypeOf((func(WriteFailure))(nil))
	if field.Type != want {
		t.Fatalf("BatchOptions.OnError type=%v, want %v", field.Type, want)
	}
	failureType := reflect.TypeOf(WriteFailure{})
	wantFields := []struct {
		name string
		typ  reflect.Type
	}{
		{"CounterKey", reflect.TypeOf("")},
		{"Delta", reflect.TypeOf("")},
		{"Member", reflect.TypeOf("")},
		{"IdempotencyKey", reflect.TypeOf("")},
		{"Err", reflect.TypeOf((*Error)(nil)).Elem()},
	}
	if failureType.NumField() != len(wantFields) {
		t.Fatalf("WriteFailure has %d fields, want exactly %d", failureType.NumField(), len(wantFields))
	}
	for i, wantField := range wantFields {
		got := failureType.Field(i)
		if got.Name != wantField.name || got.Type != wantField.typ {
			t.Errorf("WriteFailure field %d=%s %v, want %s %v", i, got.Name, got.Type, wantField.name, wantField.typ)
		}
	}
}

func TestPublishableClientUsesScopedReadPaths(t *testing.T) {
	var gotAuth, gotPath string
	client, err := NewPublishableClient(PublishableOptions{
		APIKey:  "pk_test",
		BaseURL: "https://unit.test/v1",
		HTTPClient: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			gotAuth, gotPath = r.Header.Get("Authorization"), r.URL.Path
			return jsonLoopbackResponse(200, `{"key":"visible","value":"7","epoch":0}`), nil
		})},
		MaxRetries: -1,
	})
	if err != nil {
		t.Fatal(err)
	}
	handle, err := client.Counter("visible")
	if err != nil {
		t.Fatal(err)
	}
	value, err := handle.Value(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if value.Value != "7" || gotAuth != "Bearer pk_test" || gotPath != "/v1/counters/visible/value" {
		t.Fatalf("value=%+v auth=%q path=%q", value, gotAuth, gotPath)
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestAuthAndIdempotencyHeaders(t *testing.T) {
	var auth, idem, ctype string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth, idem, ctype = r.Header.Get("Authorization"), r.Header.Get("Idempotency-Key"), r.Header.Get("Content-Type")
		_ = json.NewEncoder(w).Encode(Counter{Key: "c", Value: "1"})
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "secret", BaseURL: srv.URL + "/v1"})
	h, _ := c.Counter("c")
	if _, err := h.AddNow(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
	if auth != "Bearer secret" {
		t.Errorf("auth=%s", auth)
	}
	if idem == "" {
		t.Error("missing idempotency key")
	}
	if ctype != "application/json" {
		t.Errorf("ctype=%s", ctype)
	}
}

func TestRetryOn429ThenSuccess(t *testing.T) {
	var n int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n++
		if n < 3 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		_ = json.NewEncoder(w).Encode(Counter{Key: "c", Value: "1"})
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", Backoff: time.Millisecond})
	h, _ := c.Counter("c")
	if _, err := h.AddNow(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
	if n != 3 {
		t.Errorf("attempts=%d, want 3", n)
	}
}

func TestNoRetryOn400(t *testing.T) {
	var n int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n++
		w.WriteHeader(http.StatusBadRequest)
		_ = json.NewEncoder(w).Encode(map[string]string{"title": "bad"})
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	h, _ := c.Counter("c")
	_, err := h.AddNow(context.Background(), 1)
	ae, ok := err.(*APIError)
	if !ok || ae.Status != 400 {
		t.Fatalf("err=%v", err)
	}
	if n != 1 {
		t.Errorf("attempts=%d, should not retry", n)
	}
}

func TestBufferedCoalesceOverHTTP(t *testing.T) {
	var bodies []map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		bodies = append(bodies, b)
		_ = json.NewEncoder(w).Encode(map[string]any{"results": []any{
			map[string]any{"counterKey": "registrations", "status": "applied", "value": "6"},
		}})
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", Batch: &BatchOptions{Interval: 0}})
	h, _ := c.Counter("registrations")
	_ = h.Add(1)
	_ = h.Add(2)
	_ = h.Add(3)
	if err := c.Flush(); err != nil {
		t.Fatal(err)
	}
	if len(bodies) != 1 {
		t.Fatalf("expected 1 batch, got %d", len(bodies))
	}
	ops := bodies[0]["operations"].([]any)
	op := ops[0].(map[string]any)
	if op["amount"] != "6" || op["op"] != "add" {
		t.Errorf("op=%v", op)
	}
}

func TestRejectsBadCounterKey(t *testing.T) {
	c, _ := NewClient(Options{APIKey: "k"})
	if _, err := c.Counter("has space"); err == nil {
		t.Error("expected validation error")
	}
	if _, err := c.Counter("ok.key"); err != nil {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestAddNowAtSendsOccurredAt(t *testing.T) {
	var body map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&body)
		_ = json.NewEncoder(w).Encode(Counter{Key: "c", Value: "1", Epoch: 0})
	}))
	defer srv.Close()
	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL})
	h, _ := c.Counter("c")
	at := time.Date(2026, 7, 1, 12, 0, 0, 0, time.UTC)
	if _, err := h.AddNowAt(context.Background(), 1, at); err != nil {
		t.Fatalf("AddNowAt: %v", err)
	}
	if body["occurredAt"] != "2026-07-01T12:00:00Z" {
		t.Errorf("occurredAt = %q", body["occurredAt"])
	}
	body = nil // decoding reuses the map otherwise
	if _, err := h.AddNow(context.Background(), 1); err != nil {
		t.Fatalf("AddNow: %v", err)
	}
	if _, present := body["occurredAt"]; present {
		t.Errorf("plain AddNow must not send occurredAt, got %q", body["occurredAt"])
	}
}

func TestBignumOverTheWire(t *testing.T) {
	const sent = "100000000000000000000000000000000"     // 10^32, exceeds uint64
	const returned = "100000000000000000000000000000001" // sent + 1
	bigSent, ok := new(big.Int).SetString(sent, 10)
	if !ok {
		t.Fatalf("SetString(%q) failed", sent)
	}
	for _, tc := range []struct {
		name   string
		amount any
	}{
		{"string", sent},
		{"*big.Int", bigSent},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var rawBody []byte
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				rawBody, _ = io.ReadAll(r.Body)
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"key":"big","value":"` + returned + `","epoch":0}`))
			}))
			defer srv.Close()

			c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
			h, _ := c.Counter("big")
			ctr, err := h.AddNow(context.Background(), tc.amount)
			if err != nil {
				t.Fatal(err)
			}
			var fields map[string]json.RawMessage
			if err := json.Unmarshal(rawBody, &fields); err != nil {
				t.Fatalf("request body %s: %v", rawBody, err)
			}
			if got, want := string(fields["amount"]), `"`+sent+`"`; got != want {
				t.Errorf("wire amount = %s, want JSON string %s", got, want)
			}
			if ctr.Value != returned {
				t.Errorf("value = %q, want %q", ctr.Value, returned)
			}
			gotBig, ok := new(big.Int).SetString(ctr.Value, 10)
			wantBig := new(big.Int).Add(bigSent, big.NewInt(1))
			if !ok || gotBig.Cmp(wantBig) != 0 {
				t.Errorf("big.Int value = %v, want %v", gotBig, wantBig)
			}
		})
	}
}

func TestUsageMethod(t *testing.T) {
	var path, idem string
	c := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
		path = r.URL.Path
		idem = r.Header.Get("Idempotency-Key")
		return jsonLoopbackResponse(200, `{"month":"2026-07","ops":{"used":42,"quota":null,"resetsAt":"2026-08-01T00:00:00Z"},"counters":{"used":3,"max":1000},"limits":{"rateLimitRps":50,"maxCounters":1000,"monthlyOpsQuota":null}}`), nil
	})
	u, err := c.Usage(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if path != "/v1/usage" {
		t.Errorf("path=%s", path)
	}
	if idem != "" {
		t.Errorf("usage must not carry idempotency key, got %q", idem)
	}
	wantReset := time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)
	if u.Month != "2026-07" || u.Operations.Used != 42 || u.Operations.Quota != nil ||
		u.Limits.RateLimitRequestsPerSecond != 50 || u.Limits.MaxCounters != 1000 ||
		u.Limits.MonthlyOperationsQuota != nil || !u.Operations.ResetsAt.Equal(wantReset) {
		t.Errorf("usage=%+v", u)
	}
}

func TestMemberHandleMethodsLoopback(t *testing.T) {
	const huge = "170141183460469231731687303715884105728"
	at := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	var seen []struct {
		method string
		path   string
		query  url.Values
		idem   string
		body   map[string]string
	}
	c := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
		var body map[string]string
		if r.Body != nil {
			raw, _ := io.ReadAll(r.Body)
			if len(raw) > 0 {
				_ = json.Unmarshal(raw, &body)
			}
		}
		seen = append(seen, struct {
			method string
			path   string
			query  url.Values
			idem   string
			body   map[string]string
		}{r.Method, r.URL.EscapedPath(), r.URL.Query(), r.Header.Get("Idempotency-Key"), body})

		switch {
		case strings.HasSuffix(r.URL.Path, "/add"):
			return jsonLoopbackResponse(200, `{"key":"lb","member":"alice|bob","memberValue":"`+huge+`","memberAccepted":true,"mode":"sum","epoch":0,"value":"`+huge+`"}`), nil
		case strings.HasSuffix(r.URL.Path, "/subtract"):
			return jsonLoopbackResponse(200, `{"key":"lb","member":"alice|bob","memberValue":"3","memberAccepted":true,"mode":"sum","epoch":0,"value":"3"}`), nil
		case strings.HasSuffix(r.URL.Path, "/submit"):
			return jsonLoopbackResponse(200, `{"key":"lb","member":"alice|bob","memberValue":"-42","memberAccepted":true,"mode":"min","epoch":0}`), nil
		case r.Method == http.MethodDelete:
			return jsonLoopbackResponse(200, `{"key":"lb","member":"alice|bob","epoch":0,"value":"0"}`), nil
		default:
			return jsonLoopbackResponse(200, `{"key":"lb","member":"alice|bob","value":"3","metadata":"room1:500","rank":1,"percentile":"100.00","memberCount":1,"mode":"sum","epoch":0,"updatedAt":"2026-01-01T00:00:00Z"}`), nil
		}
	})
	h, err := c.Counter("lb")
	if err != nil {
		t.Fatal(err)
	}
	m, err := h.Member("alice|bob")
	if err != nil {
		t.Fatal(err)
	}

	added, err := m.Add(context.Background(), huge, MemberWriteOpts{Metadata: "room1:500", OccurredAt: &at})
	if err != nil {
		t.Fatal(err)
	}
	if added.MemberValue != huge || added.Value == nil || *added.Value != huge {
		t.Errorf("member add result=%+v", added)
	}
	if _, err := m.Subtract(context.Background(), "3"); err != nil {
		t.Fatal(err)
	}
	submitted, err := m.Submit(context.Background(), "-42", SubmitOpts{Mode: "min"})
	if err != nil {
		t.Fatal(err)
	}
	if submitted.MemberValue != "-42" || submitted.Value != nil {
		t.Errorf("submit result=%+v", submitted)
	}
	epoch := int64(0)
	snap, err := m.Get(context.Background(), MemberGetParams{Epoch: &epoch, Order: "desc"})
	if err != nil {
		t.Fatal(err)
	}
	if snap.Metadata == nil || *snap.Metadata != "room1:500" || snap.Percentile != "100.00" {
		t.Errorf("snapshot=%+v", snap)
	}
	removed, err := m.Remove(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if removed.Value == nil || *removed.Value != "0" {
		t.Errorf("removed=%+v", removed)
	}

	if seen[0].method != http.MethodPost || seen[0].path != "/v1/counters/lb/members/alice%7Cbob/add" {
		t.Errorf("add request=%+v", seen[0])
	}
	if got := seen[0].body; got["amount"] != huge || got["metadata"] != "room1:500" || got["occurredAt"] != "2026-01-01T00:00:00Z" {
		t.Errorf("add body=%v", got)
	}
	if got := seen[2].body; got["value"] != "-42" || got["mode"] != "min" {
		t.Errorf("submit body=%v", got)
	}
	if seen[3].query.Get("epoch") != "0" || seen[3].query.Get("order") != "desc" || seen[3].idem != "" {
		t.Errorf("member get request=%+v", seen[3])
	}
	idems := map[string]bool{}
	for _, i := range []int{0, 1, 2, 4} {
		if seen[i].idem == "" {
			t.Fatalf("write %d missing idempotency key", i)
		}
		if idems[seen[i].idem] {
			t.Fatalf("idempotency key reused: %s", seen[i].idem)
		}
		idems[seen[i].idem] = true
	}
}

func TestMemberValidationDoesNotIssueRequest(t *testing.T) {
	var calls int
	c := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
		calls++
		return jsonLoopbackResponse(200, `{}`), nil
	})
	h, _ := c.Counter("lb")
	if _, err := h.Member("has space"); err == nil {
		t.Fatal("expected invalid member key error")
	}
	m, _ := h.Member("alice")
	if _, err := m.Add(context.Background(), 1, MemberWriteOpts{Metadata: strings.Repeat("€", 342)}); err == nil {
		t.Fatal("expected metadata validation error")
	}
	if calls != 0 {
		t.Fatalf("validation failures issued %d request(s)", calls)
	}
}

func TestSubtractNowPathAndBody(t *testing.T) {
	var method, path string
	var body map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method, path = r.Method, r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&body)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(Counter{Key: "c", Value: "-3", Epoch: 0})
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	h, _ := c.Counter("c")
	ctr, err := h.SubtractNow(context.Background(), 3)
	if err != nil {
		t.Fatal(err)
	}
	if method != http.MethodPost || path != "/v1/counters/c/subtract" {
		t.Errorf("%s %s", method, path)
	}
	if body["amount"] != "3" {
		t.Errorf("amount=%q", body["amount"])
	}
	if ctr.Value != "-3" {
		t.Errorf("value=%s", ctr.Value)
	}
}

func TestListPagination(t *testing.T) {
	var paths []string
	var queries []url.Values
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		queries = append(queries, r.URL.Query())
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Query().Get("cursor") == "" {
			_ = json.NewEncoder(w).Encode(CounterPage{
				Data:       []Counter{{Key: "a", Value: "1"}, {Key: "b", Value: "18446744073709551616"}},
				NextCursor: "cur2",
			})
		} else {
			_ = json.NewEncoder(w).Encode(CounterPage{Data: []Counter{{Key: "c", Value: "3"}}})
		}
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	p1, err := c.List(context.Background(), "", 2)
	if err != nil {
		t.Fatal(err)
	}
	if paths[0] != "/v1/counters" {
		t.Errorf("path=%s", paths[0])
	}
	if queries[0].Get("limit") != "2" || queries[0].Has("cursor") {
		t.Errorf("first page query=%v", queries[0])
	}
	if p1.NextCursor != "cur2" || len(p1.Data) != 2 || p1.Data[1].Value != "18446744073709551616" {
		t.Errorf("page1=%+v", p1)
	}

	p2, err := c.List(context.Background(), p1.NextCursor, 2)
	if err != nil {
		t.Fatal(err)
	}
	if queries[1].Get("cursor") != "cur2" || queries[1].Get("limit") != "2" {
		t.Errorf("second page query=%v", queries[1])
	}
	if p2.NextCursor != "" || len(p2.Data) != 1 || p2.Data[0].Key != "c" || p2.Data[0].Value != "3" {
		t.Errorf("page2=%+v", p2)
	}
}

func TestSeriesQueryEncodingAndPoints(t *testing.T) {
	from := time.Date(2026, 7, 1, 0, 0, 0, 0, time.UTC)
	to := time.Date(2026, 7, 2, 0, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		name   string
		params SeriesParams
		want   map[string]string // "" means the key must be absent
	}{
		{"all params", SeriesParams{From: from, To: to, Bucket: "1h", Mode: "delta", TimeZone: "Europe/Amsterdam", Gapfill: true},
			map[string]string{"from": "2026-07-01T00:00:00Z", "to": "2026-07-02T00:00:00Z", "bucket": "1h", "mode": "delta", "tz": "Europe/Amsterdam", "gapfill": "true"}},
		{"minimal", SeriesParams{From: from, To: to, Bucket: "1d"},
			map[string]string{"from": "2026-07-01T00:00:00Z", "to": "2026-07-02T00:00:00Z", "bucket": "1d", "mode": "", "tz": "", "gapfill": ""}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var gotPath string
			var gotQuery url.Values
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotPath, gotQuery = r.URL.Path, r.URL.Query()
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"counterKey":"c","bucket":"` + tc.params.Bucket + `","mode":"delta","tz":"UTC",` +
					`"range":{"from":"2026-07-01T00:00:00Z","to":"2026-07-02T00:00:00Z"},` +
					`"points":[{"t":"2026-07-01T00:00:00Z","v":"1"},{"t":"2026-07-01T01:00:00Z","v":"100000000000000000000000000000000"}]}`))
			}))
			defer srv.Close()

			c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
			h, _ := c.Counter("c")
			s, err := h.Series(context.Background(), tc.params)
			if err != nil {
				t.Fatal(err)
			}
			if gotPath != "/v1/counters/c/series" {
				t.Errorf("path=%s", gotPath)
			}
			for k, want := range tc.want {
				if want == "" {
					if gotQuery.Has(k) {
						t.Errorf("query %s should be absent, got %q", k, gotQuery.Get(k))
					}
				} else if got := gotQuery.Get(k); got != want {
					t.Errorf("query %s=%q, want %q", k, got, want)
				}
			}
			if s.CounterKey != "c" || s.Bucket != tc.params.Bucket {
				t.Errorf("series=%+v", s)
			}
			if s.TimeZone != "UTC" || !s.Range.From.Equal(from) || !s.Range.To.Equal(to) {
				t.Errorf("series timezone/range=%q %v..%v", s.TimeZone, s.Range.From, s.Range.To)
			}
			if len(s.Points) != 2 || s.Points[0].Value != "1" || s.Points[1].Value != "100000000000000000000000000000000" {
				t.Errorf("points=%+v", s.Points)
			}
			if !s.Points[0].Timestamp.Equal(from) || !s.Points[1].Timestamp.Equal(from.Add(time.Hour)) {
				t.Errorf("point timestamps=%v, want %v and %v", s.Points, from, from.Add(time.Hour))
			}
		})
	}
}

// TestSeriesConformance drives conformance/series/cases.json through the real client (B8/B9):
// series params -> exact query encoding (presence-exact), member/group variants, and response parsing.
func TestSeriesConformance(t *testing.T) {
	v := loadVectors(t, "series/cases.json")

	for _, raw := range v["query"].([]any) {
		c := raw.(map[string]any)
		name := c["name"].(string)
		p := c["params"].(map[string]any)
		caseName := "query/" + name
		t.Run(caseName, func(t *testing.T) {
			if _, ok := c["expect"].(map[string]any); ok {
				// Go exposes member and group series as separate methods, so the invalid "both set"
				// state cannot be represented by the public API. There is no request to encode.
				return
			}
			want := c["query"].(map[string]any)
			from, err := time.Parse(time.RFC3339, p["from"].(string))
			if err != nil {
				t.Fatal(err)
			}
			to, err := time.Parse(time.RFC3339, p["to"].(string))
			if err != nil {
				t.Fatal(err)
			}
			params := SeriesParams{From: from, To: to, Bucket: p["bucket"].(string)}
			if tz, ok := p["tz"].(string); ok {
				params.TimeZone = tz
			}
			if gf, ok := p["gapfill"].(bool); ok {
				params.Gapfill = gf
			}
			if mode, ok := p["mode"].(string); ok {
				params.Mode = mode
			}
			var gotQuery url.Values
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotQuery = r.URL.Query()
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"counterKey":"c","bucket":"` + params.Bucket + `","mode":"delta","range":{"from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z"},"points":[]}`))
			}))
			defer srv.Close()
			cl, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
			h, _ := cl.Counter("c")
			switch {
			case p["member"] != nil:
				if _, err := h.MemberSeries(context.Background(), p["member"].(string), params); err != nil {
					t.Fatal(err)
				}
			case p["groupBy"] != nil:
				if _, err := h.GroupSeries(context.Background(), params); err != nil {
					t.Fatal(err)
				}
			default:
				if _, err := h.Series(context.Background(), params); err != nil {
					t.Fatal(err)
				}
			}
			// presence-exact: every listed key present with that value, and nothing else on the wire.
			if len(gotQuery) != len(want) {
				t.Errorf("query keys = %v, want exactly %v", gotQuery, want)
			}
			for k, wv := range want {
				if got := gotQuery.Get(k); got != wv.(string) {
					t.Errorf("query %s=%q, want %q", k, got, wv)
				}
			}
		})
	}

	for _, raw := range v["parse"].([]any) {
		c := raw.(map[string]any)
		name := c["name"].(string)
		body := c["body"].(map[string]any)
		exp := c["expect"].(map[string]any)
		t.Run("parse/"+name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_ = json.NewEncoder(w).Encode(body)
			}))
			defer srv.Close()
			rng := body["range"].(map[string]any)
			from, _ := time.Parse(time.RFC3339, rng["from"].(string))
			to, _ := time.Parse(time.RFC3339, rng["to"].(string))
			cl, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
			h, _ := cl.Counter(body["counterKey"].(string))
			params := SeriesParams{From: from, To: to, Bucket: body["bucket"].(string)}
			switch c["kind"] {
			case "memberSeries":
				s, err := h.MemberSeries(context.Background(), body["member"].(string), params)
				if err != nil {
					t.Fatal(err)
				}
				if s.CounterKey != exp["counterKey"].(string) || s.Member != exp["member"].(string) || s.Bucket != exp["bucket"].(string) || s.Mode != exp["mode"].(string) {
					t.Errorf("member series=%+v", s)
				}
				assertTimeRange(t, s.Range.From, s.Range.To, rng)
				assertSeriesPoints(t, s.Points, exp["points"].([]any))
			case "memberGroupSeries":
				s, err := h.GroupSeries(context.Background(), params)
				if err != nil {
					t.Fatal(err)
				}
				if s.CounterKey != exp["counterKey"].(string) || s.Bucket != exp["bucket"].(string) {
					t.Errorf("member group series=%+v", s)
				}
				assertTimeRange(t, s.Range.From, s.Range.To, rng)
				expSeries := exp["series"].([]any)
				if len(s.Series) != len(expSeries) {
					t.Fatalf("series len=%d, want %d", len(s.Series), len(expSeries))
				}
				for i, es := range expSeries {
					esm := es.(map[string]any)
					if s.Series[i].Member != esm["member"].(string) {
						t.Errorf("series %d member=%q, want %q", i, s.Series[i].Member, esm["member"])
					}
					assertSeriesPoints(t, s.Series[i].Points, esm["points"].([]any))
				}
			default:
				s, err := h.Series(context.Background(), params)
				if err != nil {
					t.Fatal(err)
				}
				if s.CounterKey != exp["counterKey"].(string) || s.Bucket != exp["bucket"].(string) || s.Mode != exp["mode"].(string) {
					t.Errorf("series=%+v", s)
				}
				assertTimeRange(t, s.Range.From, s.Range.To, rng)
				assertSeriesPoints(t, s.Points, exp["points"].([]any))
			}
		})
	}
}

func assertTimeRange(t *testing.T, gotFrom, gotTo time.Time, want map[string]any) {
	t.Helper()
	wantFrom, err := time.Parse(time.RFC3339, want["from"].(string))
	if err != nil {
		t.Fatal(err)
	}
	wantTo, err := time.Parse(time.RFC3339, want["to"].(string))
	if err != nil {
		t.Fatal(err)
	}
	if !gotFrom.Equal(wantFrom) || !gotTo.Equal(wantTo) {
		t.Errorf("range=%v..%v, want %v..%v", gotFrom, gotTo, wantFrom, wantTo)
	}
}

func assertSeriesPoints(t *testing.T, got []SeriesPoint, want []any) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("points len=%d, want %d", len(got), len(want))
	}
	for i, ep := range want {
		epm := ep.(map[string]any)
		wantTimestamp, err := time.Parse(time.RFC3339, epm["t"].(string))
		if err != nil {
			t.Fatalf("point %d has invalid expected timestamp: %v", i, err)
		}
		if !got[i].Timestamp.Equal(wantTimestamp) || got[i].Value != epm["v"].(string) {
			t.Errorf("point %d = %+v, want %v", i, got[i], epm)
		}
	}
}

func TestSeriesModeSkipsAreRetired(t *testing.T) {
	v := loadVectors(t, "series/cases.json")
	got := seriesModeQueryCaseNames(v)
	// Go exposes SeriesParams.Mode, so the structural skip list is empty
	// and these mode vectors run in TestSeriesConformance like every other query vector.
	want := []string{
		"query/mode-passthrough",
		"query/all-params",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("series mode query cases = %v, want exactly %v", got, want)
	}
}

// Negative limit/offset must reach the wire so the server's 400 surfaces; only the
// zero value means "unset" (Go zero-value idiom — matches the TS reference's passthrough).
func TestLeaderboardNegativePaginationPassesThrough(t *testing.T) {
	var got url.Values
	cl := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
		got = r.URL.Query()
		return jsonLoopbackResponse(200, `{"key":"k","mode":"sum","epoch":0,"order":"desc","memberCount":0,"limit":100,"offset":0,"entries":[]}`), nil
	})
	h, _ := cl.Counter("k")
	if _, err := h.Leaderboard(context.Background(), LeaderboardParams{Limit: -5, Offset: -1}); err != nil {
		t.Fatal(err)
	}
	if got.Get("limit") != "-5" || got.Get("offset") != "-1" {
		t.Fatalf("negative pagination not transmitted: limit=%q offset=%q", got.Get("limit"), got.Get("offset"))
	}
}

func TestLeaderboardConformance(t *testing.T) {
	v := loadVectors(t, "leaderboard/cases.json")

	for _, raw := range v["encodeQuery"].([]any) {
		c := raw.(map[string]any)
		t.Run("query/"+c["name"].(string), func(t *testing.T) {
			params := c["params"].(map[string]any)
			if _, ok := c["expect"].(map[string]any); ok {
				var calls int
				cl := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
					calls++
					return jsonLoopbackResponse(200, `{}`), nil
				})
				h, _ := cl.Counter("k")
				_, err := h.WindowLeaderboard(context.Background(), WindowLeaderboardParams{Window: params["window"].(string)})
				var ve *ValidationError
				if !errors.As(err, &ve) {
					t.Fatalf("got %T, want *ValidationError", err)
				}
				if calls != 0 {
					t.Fatalf("invalid window issued %d request(s)", calls)
				}
				return
			}

			var got url.Values
			cl := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
				got = r.URL.Query()
				if w, ok := params["window"].(string); ok {
					return jsonLoopbackResponse(200, `{"key":"k","mode":"sum","window":"`+w+`","order":"desc","total":"0","memberCount":0,"limit":100,"offset":0,"effectiveStart":"2026-01-01T00:00:00Z","effectiveEnd":"2026-01-02T00:00:00Z","entries":[]}`), nil
				}
				return jsonLoopbackResponse(200, `{"key":"k","mode":"sum","epoch":0,"order":"desc","memberCount":0,"limit":100,"offset":0,"entries":[]}`), nil
			})
			h, _ := cl.Counter("k")
			if w, ok := params["window"].(string); ok {
				_, err := h.WindowLeaderboard(context.Background(), WindowLeaderboardParams{
					Window: w,
					Limit:  intFromAny(params["limit"]),
					Offset: intFromAny(params["offset"]),
					Order:  stringFromAny(params["order"]),
					Epoch:  int64PtrFromAny(params["epoch"]),
				})
				if err != nil {
					t.Fatal(err)
				}
			} else {
				_, err := h.Leaderboard(context.Background(), LeaderboardParams{
					Limit:  intFromAny(params["limit"]),
					Offset: intFromAny(params["offset"]),
					Order:  stringFromAny(params["order"]),
					Epoch:  int64PtrFromAny(params["epoch"]),
				})
				if err != nil {
					t.Fatal(err)
				}
			}
			want := c["query"].(map[string]any)
			assertQueryExact(t, got, want)
		})
	}

	for _, raw := range v["encodeBody"].([]any) {
		c := raw.(map[string]any)
		t.Run("body/"+c["name"].(string), func(t *testing.T) {
			var got map[string]string
			cl := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
				rawBody, _ := io.ReadAll(r.Body)
				if err := json.Unmarshal(rawBody, &got); err != nil {
					t.Fatal(err)
				}
				return jsonLoopbackResponse(200, `{"key":"k","member":"m","memberValue":"0","memberAccepted":true,"mode":"sum","epoch":0}`), nil
			})
			h, _ := cl.Counter("k")
			m, _ := h.Member("m")
			input := c["input"].(map[string]any)
			opts := memberWriteOptsFromInput(t, input)
			var err error
			switch c["op"].(string) {
			case "memberAdd":
				_, err = m.Add(context.Background(), input["amount"].(string), opts)
			case "memberSubtract":
				_, err = m.Subtract(context.Background(), input["amount"].(string), opts)
			case "memberSubmit":
				_, err = m.Submit(context.Background(), input["value"].(string), SubmitOpts{
					Mode:       stringFromAny(input["mode"]),
					Metadata:   opts.Metadata,
					OccurredAt: opts.OccurredAt,
				})
			default:
				t.Fatalf("unknown op %v", c["op"])
			}
			if err != nil {
				t.Fatal(err)
			}
			want := c["body"].(map[string]any)
			if len(got) != len(want) {
				t.Fatalf("body=%v, want exactly %v", got, want)
			}
			for k, wv := range want {
				if got[k] != wv.(string) {
					t.Errorf("body %s=%q, want %q", k, got[k], wv)
				}
			}
		})
	}

	for _, raw := range v["parse"].([]any) {
		c := raw.(map[string]any)
		t.Run("parse/"+c["name"].(string), func(t *testing.T) {
			body := c["body"].(map[string]any)
			exp := c["expect"].(map[string]any)
			cl := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
				return jsonLoopbackResponse(200, jsonString(t, body)), nil
			})
			h, _ := cl.Counter(body["key"].(string))
			switch c["kind"].(string) {
			case "leaderboard":
				lb, err := h.Leaderboard(context.Background(), LeaderboardParams{})
				if err != nil {
					t.Fatal(err)
				}
				assertLeaderboard(t, lb, exp)
			case "windowLeaderboard":
				lb, err := h.WindowLeaderboard(context.Background(), WindowLeaderboardParams{Window: body["window"].(string)})
				if err != nil {
					t.Fatal(err)
				}
				assertWindowLeaderboard(t, lb, exp)
			case "memberValue":
				m, _ := h.Member(body["member"].(string))
				mv, err := m.Add(context.Background(), 0)
				if err != nil {
					t.Fatal(err)
				}
				assertMemberValue(t, mv, exp)
			case "memberSnapshot":
				m, _ := h.Member(body["member"].(string))
				s, err := m.Get(context.Background(), MemberGetParams{})
				if err != nil {
					t.Fatal(err)
				}
				assertMemberSnapshot(t, s, exp)
			case "memberRemoved":
				m, _ := h.Member(body["member"].(string))
				removed, err := m.Remove(context.Background())
				if err != nil {
					t.Fatal(err)
				}
				assertMemberRemoved(t, removed, exp)
			default:
				t.Fatalf("unknown kind %v", c["kind"])
			}
		})
	}
}

func stringFromAny(v any) string {
	if v == nil {
		return ""
	}
	return v.(string)
}

func assertQueryExact(t *testing.T, got url.Values, want map[string]any) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("query=%v, want exactly %v", got, want)
	}
	for k, wv := range want {
		if got.Get(k) != wv.(string) {
			t.Errorf("query %s=%q, want %q", k, got.Get(k), wv)
		}
	}
}

func memberWriteOptsFromInput(t *testing.T, input map[string]any) MemberWriteOpts {
	t.Helper()
	var opts MemberWriteOpts
	if md, ok := input["metadata"].(string); ok {
		opts.Metadata = md
	}
	if at, ok := input["occurredAt"].(string); ok {
		parsed, err := time.Parse(time.RFC3339, at)
		if err != nil {
			t.Fatal(err)
		}
		opts.OccurredAt = &parsed
	}
	return opts
}

func assertLeaderboard(t *testing.T, got *Leaderboard, exp map[string]any) {
	t.Helper()
	if got.Key != exp["key"].(string) || got.Mode != exp["mode"].(string) || got.Epoch != int64(exp["epoch"].(float64)) || got.Order != exp["order"].(string) {
		t.Errorf("leaderboard=%+v, expect=%v", got, exp)
	}
	if exp["totalAbsent"] == true {
		if got.Total != nil {
			t.Errorf("total should be absent, got %q", *got.Total)
		}
	} else if total, ok := exp["total"].(string); ok {
		if got.Total == nil || *got.Total != total {
			t.Errorf("total=%v, want %q", got.Total, total)
		}
	}
	if got.MemberCount != intFromAny(exp["memberCount"]) {
		t.Errorf("memberCount=%d, want %v", got.MemberCount, exp["memberCount"])
	}
	assertLeaderboardEntries(t, got.Entries, exp["entries"].([]any))
}

func assertLeaderboardEntries(t *testing.T, got []LeaderboardEntry, want []any) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("entries len=%d, want %d", len(got), len(want))
	}
	for i, raw := range want {
		exp := raw.(map[string]any)
		if got[i].Rank != intFromAny(exp["rank"]) || got[i].Member != exp["member"].(string) || got[i].Value != exp["value"].(string) {
			t.Errorf("entry %d=%+v, want %v", i, got[i], exp)
		}
		if md, ok := exp["metadata"].(string); ok {
			if got[i].Metadata == nil || *got[i].Metadata != md {
				t.Errorf("entry %d metadata=%v, want %q", i, got[i].Metadata, md)
			}
		}
	}
}

func TestWindowLeaderboardOnScoreBoardHasBoardModeAndNoTotal(t *testing.T) {
	// A windowed board follows the board mode; score boards omit `total` entirely on the wire.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"key":"best-lap","mode":"min","window":"7d","order":"asc",` +
			`"memberCount":2,"limit":100,"offset":0,` +
			`"effectiveStart":"2026-06-27T00:00:00Z","effectiveEnd":"2026-07-04T09:30:00Z",` +
			`"entries":[{"rank":1,"member":"alice","value":"1417"}]}`))
	}))
	defer srv.Close()
	client, err := NewClient(Options{APIKey: "k", BaseURL: srv.URL})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	h, err := client.Counter("best-lap")
	if err != nil {
		t.Fatal(err)
	}
	lb, err := h.WindowLeaderboard(context.Background(), WindowLeaderboardParams{Window: "7d"})
	if err != nil {
		t.Fatal(err)
	}
	if lb.Mode != "min" {
		t.Errorf("mode=%q, want min", lb.Mode)
	}
	if lb.Total != nil {
		t.Errorf("total must be nil on score-board windows, got %q", *lb.Total)
	}
	if len(lb.Entries) != 1 || lb.Entries[0].Value != "1417" {
		t.Errorf("entries=%+v", lb.Entries)
	}
}

func assertWindowLeaderboard(t *testing.T, got *WindowLeaderboard, exp map[string]any) {
	t.Helper()
	if got.Key != exp["key"].(string) || got.Mode != exp["mode"].(string) || got.Window != exp["window"].(string) || got.Order != exp["order"].(string) {
		t.Errorf("window leaderboard=%+v, expect=%v", got, exp)
	}
	// The window group total is non-nil only on sum boards; score boards (min/max/latest) omit it.
	if expTotal, ok := exp["total"]; ok {
		if got.Total == nil || *got.Total != expTotal.(string) {
			t.Errorf("window total=%v, want %v", got.Total, expTotal)
		}
	} else if got.Total != nil {
		t.Errorf("window total=%v, want nil (score board)", *got.Total)
	}
	wantStart, err := time.Parse(time.RFC3339, exp["effectiveStart"].(string))
	if err != nil {
		t.Fatal(err)
	}
	wantEnd, err := time.Parse(time.RFC3339, exp["effectiveEnd"].(string))
	if err != nil {
		t.Fatal(err)
	}
	if !got.EffectiveStart.Equal(wantStart) || !got.EffectiveEnd.Equal(wantEnd) {
		t.Errorf("effective range=%s..%s", got.EffectiveStart, got.EffectiveEnd)
	}
	want := exp["entries"].([]any)
	if len(got.Entries) != len(want) {
		t.Fatalf("entries len=%d, want %d", len(got.Entries), len(want))
	}
	for i, raw := range want {
		e := raw.(map[string]any)
		if got.Entries[i].Rank != intFromAny(e["rank"]) || got.Entries[i].Member != e["member"].(string) || got.Entries[i].Value != e["value"].(string) {
			t.Errorf("window entry %d=%+v, want %v", i, got.Entries[i], e)
		}
	}
}

func assertMemberValue(t *testing.T, got *MemberValue, exp map[string]any) {
	t.Helper()
	if got.Key != exp["key"].(string) || got.Member != exp["member"].(string) || got.MemberValue != exp["memberValue"].(string) || got.MemberAccepted != exp["memberAccepted"].(bool) || got.Mode != exp["mode"].(string) || got.Epoch != int64(exp["epoch"].(float64)) {
		t.Errorf("member value=%+v, expect=%v", got, exp)
	}
	if exp["valueAbsent"] == true {
		if got.Value != nil {
			t.Errorf("value should be absent, got %q", *got.Value)
		}
	} else if value, ok := exp["value"].(string); ok {
		if got.Value == nil || *got.Value != value {
			t.Errorf("value=%v, want %q", got.Value, value)
		}
	}
}

func assertMemberSnapshot(t *testing.T, got *MemberSnapshot, exp map[string]any) {
	t.Helper()
	if got.Key != exp["key"].(string) || got.Member != exp["member"].(string) || got.Value != exp["value"].(string) || got.Rank != intFromAny(exp["rank"]) || got.Percentile != exp["percentile"].(string) || got.MemberCount != intFromAny(exp["memberCount"]) || got.Mode != exp["mode"].(string) || got.Epoch != int64(exp["epoch"].(float64)) {
		t.Errorf("member snapshot=%+v, expect=%v", got, exp)
	}
	if md, ok := exp["metadata"].(string); ok {
		if got.Metadata == nil || *got.Metadata != md {
			t.Errorf("metadata=%v, want %q", got.Metadata, md)
		}
	}
}

func assertMemberRemoved(t *testing.T, got *MemberRemoved, exp map[string]any) {
	t.Helper()
	if got.Key != exp["key"].(string) || got.Member != exp["member"].(string) || got.Epoch != int64(exp["epoch"].(float64)) {
		t.Errorf("member removed=%+v, expect=%v", got, exp)
	}
	if value, ok := exp["value"].(string); ok {
		if got.Value == nil || *got.Value != value {
			t.Errorf("value=%v, want %q", got.Value, value)
		}
	}
}

func TestDerivedConformance(t *testing.T) {
	v := loadVectors(t, "derived/cases.json")

	for _, raw := range v["encodeQuery"].([]any) {
		c := raw.(map[string]any)
		t.Run("query/"+c["name"].(string), func(t *testing.T) {
			params := c["params"].(map[string]any)
			var got url.Values
			cl := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
				got = r.URL.Query()
				if r.URL.Path != "/v1/derived/conversion/series" {
					t.Errorf("path=%s", r.URL.Path)
				}
				return jsonLoopbackResponse(200, `{"key":"conversion","bucket":"`+params["bucket"].(string)+`","scale":6,"range":{"from":"2026-01-01T00:00:00Z","to":"2026-01-02T00:00:00Z"},"points":[]}`), nil
			})
			d, err := cl.Derived("conversion")
			if err != nil {
				t.Fatal(err)
			}
			from, _ := time.Parse(time.RFC3339, params["from"].(string))
			to, _ := time.Parse(time.RFC3339, params["to"].(string))
			_, err = d.Series(context.Background(), DerivedSeriesParams{
				From:     from,
				To:       to,
				Bucket:   params["bucket"].(string),
				TimeZone: stringFromAny(params["tz"]),
			})
			if err != nil {
				t.Fatal(err)
			}
			want := c["query"].(map[string]any)
			assertQueryExact(t, got, want)
			for _, absent := range c["absent"].([]any) {
				if got.Has(absent.(string)) {
					t.Errorf("query key %q should be absent in %v", absent, got)
				}
			}
		})
	}

	for _, raw := range v["parse"].([]any) {
		c := raw.(map[string]any)
		t.Run("parse/"+c["name"].(string), func(t *testing.T) {
			body := c["body"].(map[string]any)
			exp := c["expect"].(map[string]any)
			cl := loopbackClient(t, func(r *http.Request) (*http.Response, error) {
				return jsonLoopbackResponse(200, jsonString(t, body)), nil
			})
			d, err := cl.Derived(body["key"].(string))
			if err != nil {
				t.Fatal(err)
			}
			switch c["kind"].(string) {
			case "derivedValue":
				got, err := d.Value(context.Background())
				if err != nil {
					t.Fatal(err)
				}
				if got.Key != exp["key"].(string) || got.Scale != intFromAny(exp["scale"]) {
					t.Errorf("derived value=%+v, expect=%v", got, exp)
				}
				if exp["value"] == nil {
					if got.Value != nil {
						t.Errorf("value=%q, want nil", *got.Value)
					}
					if got.Reason == nil || *got.Reason != exp["reason"].(string) {
						t.Errorf("reason=%v, want %q", got.Reason, exp["reason"])
					}
				} else if got.Value == nil || *got.Value != exp["value"].(string) {
					t.Errorf("value=%v, want %q", got.Value, exp["value"])
				}
				wantInputs := exp["inputs"].(map[string]any)
				if len(got.Inputs) != len(wantInputs) {
					t.Errorf("inputs=%v, want %v", got.Inputs, exp["inputs"])
				}
				for k, wv := range wantInputs {
					if got.Inputs[k] != wv.(string) {
						t.Errorf("input %s=%q, want %q", k, got.Inputs[k], wv)
					}
				}
			case "derivedSeries":
				rng := body["range"].(map[string]any)
				from, _ := time.Parse(time.RFC3339, rng["from"].(string))
				to, _ := time.Parse(time.RFC3339, rng["to"].(string))
				got, err := d.Series(context.Background(), DerivedSeriesParams{From: from, To: to, Bucket: body["bucket"].(string)})
				if err != nil {
					t.Fatal(err)
				}
				if got.Key != exp["key"].(string) || got.Bucket != exp["bucket"].(string) || got.Scale != intFromAny(exp["scale"]) {
					t.Errorf("derived series=%+v, expect=%v", got, exp)
				}
				assertTimeRange(t, got.Range.From, got.Range.To, rng)
				wantPoints := exp["points"].([]any)
				if len(got.Points) != len(wantPoints) {
					t.Fatalf("points len=%d, want %d", len(got.Points), len(wantPoints))
				}
				for i, rawPoint := range wantPoints {
					wantPoint := rawPoint.(map[string]any)
					wantTimestamp, err := time.Parse(time.RFC3339, wantPoint["t"].(string))
					if err != nil {
						t.Fatal(err)
					}
					if !got.Points[i].Timestamp.Equal(wantTimestamp) {
						t.Errorf("point %d timestamp=%v, want %v", i, got.Points[i].Timestamp, wantTimestamp)
					}
					if wantPoint["v"] == nil {
						if got.Points[i].Value != nil {
							t.Errorf("point %d value=%q, want nil", i, *got.Points[i].Value)
						}
					} else if got.Points[i].Value == nil || *got.Points[i].Value != wantPoint["v"].(string) {
						t.Errorf("point %d value=%v, want %q", i, got.Points[i].Value, wantPoint["v"])
					}
				}
			default:
				t.Fatalf("unknown kind %v", c["kind"])
			}
		})
	}
}

func TestClearPostsAndParses(t *testing.T) {
	var method, path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method, path = r.Method, r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(Counter{Key: "c", Value: "0", Epoch: 2})
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	h, _ := c.Counter("c")
	ctr, err := h.Clear(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if method != http.MethodPost || path != "/v1/counters/c/clear" {
		t.Errorf("%s %s", method, path)
	}
	if ctr.Key != "c" || ctr.Value != "0" || ctr.Epoch != 2 {
		t.Errorf("counter=%+v", ctr)
	}
}

func TestDeleteMethodAnd204(t *testing.T) {
	var method, path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method, path = r.Method, r.URL.Path
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	h, _ := c.Counter("c")
	if err := h.Delete(context.Background()); err != nil {
		t.Fatal(err)
	}
	if method != http.MethodDelete || path != "/v1/counters/c" {
		t.Errorf("%s %s", method, path)
	}
}

func TestMalformed2xxBodyIsTypedError(t *testing.T) {
	for _, body := range []string{"", "{bad json", "<html>nope</html>"} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(body))
		}))
		c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
		h, _ := c.Counter("c")
		_, err := h.AddNow(context.Background(), 1)
		srv.Close()
		if err == nil {
			t.Errorf("body %q: expected error, got nil", body)
			continue
		}
		if _, ok := err.(*APIError); !ok {
			t.Errorf("body %q: expected *APIError, got %T: %v", body, err, err)
		}
	}
}

func TestEnqueueAfterCloseIsRejected(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"results":[{"counterKey":"c","status":"applied","value":"1"}]}`))
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	h, _ := c.Counter("c")
	if err := h.Add(1); err != nil {
		t.Fatalf("pre-close Add: %v", err)
	}
	if err := c.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if err := h.Add(1); err != ErrClientClosed {
		t.Errorf("post-close Add: want ErrClientClosed, got %v", err)
	}
	if n := c.batcher.pending(); n != 0 {
		t.Errorf("post-close write leaked into buffer: pending=%d", n)
	}
}

func TestBatchPerOpErrorIsSurfaced(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"results":[` +
			`{"counterKey":"a","status":"applied","value":"1"},` +
			`{"counterKey":"b","status":"error","error":{"title":"counter limit reached","status":403}}]}`))
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	ca, _ := c.Counter("a")
	cb, _ := c.Counter("b")
	_ = ca.Add(1)
	_ = cb.Add(1)
	err := c.Flush()
	if err == nil {
		t.Fatal("expected batch per-op error to surface, got nil")
	}
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("expected *APIError, got %T: %v", err, err)
	}
	if apiErr.Status != 403 {
		t.Errorf("want status 403 from the failed op, got %d", apiErr.Status)
	}
}

func TestRetryBackoffGrowsExponentially(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable) // always retryable, no Retry-After
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", MaxRetries: 3, Backoff: 100 * time.Millisecond})
	var delays []time.Duration
	c.sleepFn = func(d time.Duration) { delays = append(delays, d) }
	h, _ := c.Counter("c")
	_, _ = h.AddNow(context.Background(), 1) // exhausts retries

	if len(delays) != 3 || delays[0] != 100*time.Millisecond || delays[1] != 200*time.Millisecond || delays[2] != 400*time.Millisecond {
		t.Errorf("backoff sequence = %v, want [100ms 200ms 400ms]", delays)
	}
}

func TestRetryAfterHeaderIsHonored(t *testing.T) {
	var n int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&n, 1) == 1 {
			w.Header().Set("Retry-After", "2")
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"key":"c","value":"1","epoch":0}`))
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", Backoff: 0})
	var delays []time.Duration
	c.sleepFn = func(d time.Duration) { delays = append(delays, d) }
	h, _ := c.Counter("c")
	if _, err := h.AddNow(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
	if len(delays) != 1 || delays[0] != 2*time.Second {
		t.Errorf("delays = %v, want [2s] honoring Retry-After", delays)
	}
}

func TestHostileQueryEncoding(t *testing.T) {
	var rawQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rawQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1"})
	if _, err := c.List(context.Background(), "a&b=c#d e", 0); err != nil {
		t.Fatal(err)
	}
	vals, _ := url.ParseQuery(rawQuery)
	if vals.Get("cursor") != "a&b=c#d e" {
		t.Errorf("cursor did not round-trip: raw=%q decoded=%q", rawQuery, vals.Get("cursor"))
	}
	if !strings.Contains(rawQuery, "%26") || !strings.Contains(rawQuery, "%23") {
		t.Errorf("reserved chars not percent-escaped in %q", rawQuery)
	}
}

// TestErrorTaxonomyConformance drives conformance/errors/cases.json through the real client (B9),
// pinning B1/B2: the marker interface catches every SDK error, an HTTP error surfaces as *APIError,
// and a no-response failure surfaces as *TransportError (never an *APIError{Status: 0}).
func TestErrorTaxonomyConformance(t *testing.T) {
	v := loadVectors(t, "errors/cases.json")
	asCountersErr := func(err error) bool {
		var cerr Error
		return errors.As(err, &cerr)
	}

	for _, raw := range v["api"].([]any) {
		c := raw.(map[string]any)
		name := c["name"].(string)
		resp := c["response"].(map[string]any)
		want := c["expect"].(map[string]any)
		t.Run("api/"+name, func(t *testing.T) {
			status := int(resp["status"].(float64))
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(status)
				if resp["body"] != nil {
					_ = json.NewEncoder(w).Encode(resp["body"])
				}
			}))
			defer srv.Close()
			cl, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", MaxRetries: -1})
			h, _ := cl.Counter("c")
			_, err := h.AddNow(context.Background(), 1)
			var apiErr *APIError
			if !errors.As(err, &apiErr) {
				t.Fatalf("got %T, want *APIError", err)
			}
			if apiErr.Status != int(want["status"].(float64)) {
				t.Errorf("status = %d, want %v", apiErr.Status, want["status"])
			}
			if title, ok := want["title"].(string); ok && !strings.Contains(apiErr.Error(), title) {
				t.Errorf("error %q does not carry title %q", apiErr.Error(), title)
			}
			if !asCountersErr(err) {
				t.Error("*APIError not caught by counters.Error marker")
			}
		})
	}

	for _, raw := range v["transport"].([]any) {
		c := raw.(map[string]any)
		t.Run("transport/"+c["name"].(string), func(t *testing.T) {
			// No server listening: every attempt is a connect failure (no response ever arrives).
			closed := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
			base := closed.URL
			closed.Close()
			cl, _ := NewClient(Options{APIKey: "k", BaseURL: base + "/v1", MaxRetries: -1, Backoff: 0})
			h, _ := cl.Counter("c")
			_, err := h.AddNow(context.Background(), 1)
			var transportErr *TransportError
			if !errors.As(err, &transportErr) {
				t.Fatalf("got %T, want *TransportError", err)
			}
			var strayAPI *APIError
			if errors.As(err, &strayAPI) {
				t.Errorf("transport failure leaked an *APIError (status %d)", strayAPI.Status)
			}
			if !asCountersErr(err) {
				t.Error("*TransportError not caught by counters.Error marker")
			}
		})
	}

	for _, raw := range v["validation"].([]any) {
		c := raw.(map[string]any)
		val := c["validate"].(map[string]any)
		t.Run("validation/"+c["name"].(string), func(t *testing.T) {
			var err error
			if key, ok := val["key"].(string); ok {
				_, err = (&Client{}).Counter(key)
			} else {
				_, err = ToAmount(val["amount"].(string))
			}
			var ve *ValidationError
			if !errors.As(err, &ve) {
				t.Fatalf("got %T, want *ValidationError", err)
			}
			if !asCountersErr(err) {
				t.Error("*ValidationError not caught by counters.Error marker")
			}
		})
	}

	// batch[]: an outer HTTP 200 whose results[] carry a per-op "error" (
	// 2026-07-06, part A). A per-op problem with a status surfaces as *APIError carrying it; a
	// problem with no status (or no problem object at all) surfaces as *ValidationError — never
	// an *APIError with a fabricated status.
	for _, raw := range v["batch"].([]any) {
		c := raw.(map[string]any)
		name := c["name"].(string)
		resp := c["response"].(map[string]any)
		want := c["expect"].(map[string]any)
		t.Run("batch/"+name, func(t *testing.T) {
			status := int(resp["status"].(float64))
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(status)
				_ = json.NewEncoder(w).Encode(resp["body"])
			}))
			defer srv.Close()
			cl, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", MaxRetries: -1})
			signups, _ := cl.Counter("signups")
			capped, _ := cl.Counter("capped")
			if err := signups.Add(1); err != nil {
				t.Fatal(err)
			}
			if err := capped.Add(1); err != nil {
				t.Fatal(err)
			}
			err := cl.Flush()
			if err == nil {
				t.Fatal("expected the per-op error to surface from Flush, got nil")
			}
			switch want["taxonomy"].(string) {
			case "api":
				var apiErr *APIError
				if !errors.As(err, &apiErr) {
					t.Fatalf("got %T (%v), want *APIError", err, err)
				}
				if apiErr.Status != int(want["status"].(float64)) {
					t.Errorf("status = %d, want %v", apiErr.Status, want["status"])
				}
				if title, ok := want["title"].(string); ok && !strings.Contains(apiErr.Error(), title) {
					t.Errorf("error %q does not carry title %q", apiErr.Error(), title)
				}
			case "validation":
				var ve *ValidationError
				if !errors.As(err, &ve) {
					t.Fatalf("got %T (%v), want *ValidationError", err, err)
				}
				var strayAPI *APIError
				if errors.As(err, &strayAPI) {
					t.Errorf("status-less per-op problem leaked an *APIError (status %d)", strayAPI.Status)
				}
			default:
				t.Fatalf("unexpected taxonomy %v", want["taxonomy"])
			}
			if !asCountersErr(err) {
				t.Error("batch error not caught by counters.Error marker")
			}
		})
	}
}

func TestImmediateModeRoutesWriteIdentityToOnError(t *testing.T) {
	failureCh := make(chan WriteFailure, 1)
	c, _ := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return jsonLoopbackResponse(403, `{"title":"quota exceeded","status":403}`), nil
		})},
		Batch: &BatchOptions{Disabled: true, OnError: func(failure WriteFailure) { failureCh <- failure }},
	})
	h, _ := c.Counter("c")
	if err := h.Add(1); err != nil {
		t.Fatal(err)
	}
	select {
	case failure := <-failureCh:
		if failure.CounterKey != "c" || failure.Delta != "1" || failure.Member != "" || failure.IdempotencyKey == "" {
			t.Fatalf("OnError identity = %+v, want counter c, delta 1, no member, and an idempotency key", failure)
		}
		var apiErr *APIError
		if !errors.As(failure.Err, &apiErr) || apiErr.Status != 403 {
			t.Fatalf("OnError got %v, want *APIError with status 403", failure.Err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("immediate-mode write failure never reached OnError")
	}
}

func TestImmediateModeRejectsWritesAfterClose(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"results":[{"counterKey":"c","status":"applied","value":"1"}]}`)
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", Batch: &BatchOptions{Disabled: true}})
	h, _ := c.Counter("c")
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	if err := h.Add(1); !errors.Is(err, ErrClientClosed) {
		t.Fatalf("Add after Close = %v, want ErrClientClosed", err)
	}
}

func TestMaxRetriesMinusOneDisablesRetries(t *testing.T) {
	attempts := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(500)
		_, _ = io.WriteString(w, `{"title":"boom","status":500}`)
	}))
	defer srv.Close()

	c, _ := NewClient(Options{APIKey: "k", BaseURL: srv.URL + "/v1", MaxRetries: -1})
	h, _ := c.Counter("c")
	_, err := h.AddNow(context.Background(), 1)
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 500 {
		t.Fatalf("got %v, want *APIError 500", err)
	}
	if attempts != 1 {
		t.Fatalf("attempts = %d, want 1 (retries disabled)", attempts)
	}
}

func TestBatchIntervalZeroUsesDefaultAndMinusOneDisablesTimer(t *testing.T) {
	withDefault, err := NewClient(Options{APIKey: "k", Batch: &BatchOptions{Interval: 0}})
	if err != nil {
		t.Fatal(err)
	}
	if withDefault.batcher.ticker == nil {
		t.Fatal("BatchOptions.Interval=0 disabled the timer; zero must select the 1s default")
	}
	if err := withDefault.Close(); err != nil {
		t.Fatal(err)
	}

	withoutTimer, err := NewClient(Options{APIKey: "k", Batch: &BatchOptions{Interval: -1}})
	if err != nil {
		t.Fatal(err)
	}
	if withoutTimer.batcher.ticker != nil {
		t.Fatal("BatchOptions.Interval=-1 started a timer; -1 must disable timed flushing")
	}
	if err := withoutTimer.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestBatchOnErrorReportsOnlyFailedWriteIdentities(t *testing.T) {
	var failures []WriteFailure
	c, err := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return jsonLoopbackResponse(200, `{"results":[`+
				`{"counterKey":"accepted","status":"applied","value":"2"},`+
				`{"counterKey":"rejected","status":"error","error":{"title":"quota exceeded","status":403}}]}`), nil
		})},
		Batch: &BatchOptions{Interval: -1, OnError: func(failure WriteFailure) {
			failures = append(failures, failure)
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	accepted, _ := c.Counter("accepted")
	rejected, _ := c.Counter("rejected")
	if err := accepted.Add(2); err != nil {
		t.Fatal(err)
	}
	if err := rejected.Subtract(3); err != nil {
		t.Fatal(err)
	}
	c.batcher.flushSafe()

	if len(failures) != 1 {
		t.Fatalf("OnError calls=%d, want only the one failed result: %+v", len(failures), failures)
	}
	failure := failures[0]
	if failure.CounterKey != "rejected" || failure.Delta != "-3" || failure.Member != "" || failure.IdempotencyKey == "" {
		t.Fatalf("failure identity=%+v, want rejected/-3/no-member/non-empty-idempotency-key", failure)
	}
	var apiErr *APIError
	if !errors.As(failure.Err, &apiErr) || apiErr.Status != 403 {
		t.Fatalf("failure.Err=%v, want *APIError status 403", failure.Err)
	}
}

func TestBatchOnErrorReportsEveryUnknownWriteOnOuterFailure(t *testing.T) {
	var failures []WriteFailure
	c, err := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("network unavailable")
		})},
		Batch: &BatchOptions{Interval: -1, OnError: func(failure WriteFailure) {
			failures = append(failures, failure)
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	a, _ := c.Counter("a")
	b, _ := c.Counter("b")
	_ = a.Add(4)
	_ = b.Subtract(7)
	c.batcher.flushSafe()

	if len(failures) != 2 {
		t.Fatalf("OnError calls=%d, want one for each unknown write: %+v", len(failures), failures)
	}
	got := map[string]string{}
	for _, failure := range failures {
		got[failure.CounterKey] = failure.Delta
		if failure.IdempotencyKey == "" {
			t.Errorf("failure for %s omitted its idempotency key", failure.CounterKey)
		}
		var transportErr *TransportError
		if !errors.As(failure.Err, &transportErr) {
			t.Errorf("failure for %s has %T, want *TransportError", failure.CounterKey, failure.Err)
		}
	}
	if got["a"] != "4" || got["b"] != "-7" {
		t.Fatalf("reported deltas=%v, want a=4 and b=-7", got)
	}
}

func TestBatchResultsCorrelateByUniqueCounterKey(t *testing.T) {
	client, err := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return jsonLoopbackResponse(200, `{"results":[`+
				`{"counterKey":"beta","status":"deduplicated"},`+
				`{"counterKey":"alpha","status":"applied"}]}`), nil
		})},
		Batch: &BatchOptions{Interval: -1},
	})
	if err != nil {
		t.Fatal(err)
	}
	ops := []operation{
		{CounterKey: "alpha", Operation: "add", Amount: "2", IdempotencyKey: "idem-alpha"},
		{CounterKey: "beta", Operation: "subtract", Amount: "3", IdempotencyKey: "idem-beta"},
	}
	failures, err := client.submitBatch(context.Background(), ops)
	if err != nil || len(failures) != 0 {
		t.Fatalf("valid out-of-order results returned failures=%+v err=%v", failures, err)
	}
}

func TestBatchPerOperationProblemStatusMustBeHTTPCode(t *testing.T) {
	ops := []operation{{
		CounterKey: "alpha", Operation: "add", Amount: "2",
		IdempotencyKey: "idem-alpha",
	}}
	for _, tc := range []struct {
		name string
		body string
	}{
		{"negative", `{"results":[{"counterKey":"alpha","status":"error","error":{"title":"bad","status":-1}}]}`},
		{"zero", `{"results":[{"counterKey":"alpha","status":"error","error":{"title":"bad","status":0}}]}`},
		{"below-http-range", `{"results":[{"counterKey":"alpha","status":"error","error":{"title":"bad","status":99}}]}`},
		{"above-http-range", `{"results":[{"counterKey":"alpha","status":"error","error":{"title":"bad","status":600}}]}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client, err := NewClient(Options{
				APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
				HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
					return jsonLoopbackResponse(200, tc.body), nil
				})},
				Batch: &BatchOptions{Interval: -1},
			})
			if err != nil {
				t.Fatal(err)
			}

			failures, err := client.submitBatch(context.Background(), ops)
			assertExactlyOneTaxonomyKind(t, err, "validation")
			if len(failures) != 1 {
				t.Fatalf("failures=%d, want 1: %+v", len(failures), failures)
			}
			assertExactlyOneTaxonomyKind(t, failures[0].Err, "validation")
			if failures[0].CounterKey != "alpha" || failures[0].Delta != "2" ||
				failures[0].Member != "" || failures[0].IdempotencyKey != "idem-alpha" {
				t.Fatalf("failure identity=%+v, want alpha/2/no-member/idem-alpha", failures[0])
			}
		})
	}
}

func TestMalformedBatchResultsFanOutValidationIdentity(t *testing.T) {
	ops := []operation{
		{CounterKey: "alpha", Operation: "add", Amount: "2", IdempotencyKey: "idem-alpha"},
		{CounterKey: "beta", Operation: "subtract", Amount: "3", IdempotencyKey: "idem-beta"},
	}
	tests := []struct {
		name string
		body string
	}{
		{"missing-results", `{}`},
		{"null-results", `{"results":null}`},
		{"empty-results", `{"results":[]}`},
		{"short-results", `{"results":[{"counterKey":"alpha","status":"applied"}]}`},
		{"duplicate-key-omits-submitted-key", `{"results":[` +
			`{"counterKey":"alpha","status":"applied"},` +
			`{"counterKey":"alpha","status":"deduplicated"}]}`},
		{"unknown-key", `{"results":[` +
			`{"counterKey":"alpha","status":"applied"},` +
			`{"counterKey":"ghost","status":"applied"}]}`},
		{"unknown-status", `{"results":[` +
			`{"counterKey":"alpha","status":"queued"},` +
			`{"counterKey":"beta","status":"applied"}]}`},
		{"missing-status", `{"results":[` +
			`{"counterKey":"alpha"},` +
			`{"counterKey":"beta","status":"applied"}]}`},
		{"wrong-results-shape", `{"results":{}}`},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			client, err := NewClient(Options{
				APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
				HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
					return jsonLoopbackResponse(200, tc.body), nil
				})},
				Batch: &BatchOptions{Interval: -1},
			})
			if err != nil {
				t.Fatal(err)
			}

			failures, err := client.submitBatch(context.Background(), ops)
			assertExactlyOneTaxonomyKind(t, err, "validation")
			var validationErr *ValidationError
			if !errors.As(err, &validationErr) {
				t.Fatalf("error=%T, want *ValidationError", err)
			}
			if len(failures) != len(ops) {
				t.Fatalf("failures=%d, want one for every submitted operation (%d): %+v", len(failures), len(ops), failures)
			}
			wantDeltas := []string{"2", "-3"}
			for i, failure := range failures {
				if failure.CounterKey != ops[i].CounterKey || failure.Delta != wantDeltas[i] ||
					failure.Member != "" || failure.IdempotencyKey != ops[i].IdempotencyKey {
					t.Errorf("failure[%d]=%+v, want identity %s/%s/no-member/%s",
						i, failure, ops[i].CounterKey, wantDeltas[i], ops[i].IdempotencyKey)
				}
				if failure.Err != validationErr {
					t.Errorf("failure[%d].Err=%v, want the shared validation error %v", i, failure.Err, validationErr)
				}
			}
		})
	}
}

func TestMalformedBatchResponseOnErrorFansOutIdentities(t *testing.T) {
	var failures []WriteFailure
	client, err := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return jsonLoopbackResponse(200, `{"results":[]}`), nil
		})},
		Batch: &BatchOptions{Interval: -1, OnError: func(failure WriteFailure) {
			failures = append(failures, failure)
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	alpha, _ := client.Counter("alpha")
	beta, _ := client.Counter("beta")
	if err := alpha.Add(2); err != nil {
		t.Fatal(err)
	}
	if err := beta.Subtract(3); err != nil {
		t.Fatal(err)
	}
	client.batcher.flushSafe()

	if len(failures) != 2 {
		t.Fatalf("OnError calls=%d, want one per submitted operation: %+v", len(failures), failures)
	}
	got := make(map[string]string, len(failures))
	for _, failure := range failures {
		got[failure.CounterKey] = failure.Delta
		if failure.Member != "" || failure.IdempotencyKey == "" {
			t.Errorf("failure omitted reconciliation identity: %+v", failure)
		}
		assertExactlyOneTaxonomyKind(t, failure.Err, "validation")
	}
	if got["alpha"] != "2" || got["beta"] != "-3" {
		t.Fatalf("OnError identities=%v, want alpha=2 and beta=-3", got)
	}
}

func TestCallerCanReuseIdempotencyKeyAfterTransportFailure(t *testing.T) {
	var keys []string
	attempt := 0
	c, err := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			keys = append(keys, r.Header.Get("Idempotency-Key"))
			attempt++
			if attempt == 1 {
				return nil, errors.New("connection reset after send")
			}
			return jsonLoopbackResponse(200, `{"key":"c","value":"5","epoch":0}`), nil
		})},
		Batch: &BatchOptions{Interval: -1},
	})
	if err != nil {
		t.Fatal(err)
	}
	h, _ := c.Counter("c")
	opts := WriteOptions{IdempotencyKey: "retry-safe-1"}
	if _, err := h.AddNow(context.Background(), 5, opts); err == nil {
		t.Fatal("first write unexpectedly succeeded")
	} else {
		var transportErr *TransportError
		if !errors.As(err, &transportErr) {
			t.Fatalf("first write error=%T, want *TransportError", err)
		}
	}
	result, err := h.AddNow(context.Background(), 5, opts)
	if err != nil {
		t.Fatal(err)
	}
	if result.Value != "5" {
		t.Fatalf("retry result=%+v", result)
	}
	if !slices.Equal(keys, []string{"retry-safe-1", "retry-safe-1"}) {
		t.Fatalf("idempotency keys=%v, want the caller key reused exactly", keys)
	}
}

func TestEveryConfirmedWriteAcceptsCallerIdempotencyKey(t *testing.T) {
	var keys []string
	c, err := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			keys = append(keys, r.Header.Get("Idempotency-Key"))
			switch {
			case r.Method == http.MethodDelete && strings.Contains(r.URL.Path, "/members/"):
				return jsonLoopbackResponse(200, `{"key":"c","member":"alice","removed":true,"epoch":0,"value":"0"}`), nil
			case r.Method == http.MethodDelete:
				return jsonLoopbackResponse(204, ""), nil
			case strings.Contains(r.URL.Path, "/members/"):
				return jsonLoopbackResponse(200, `{"key":"c","member":"alice","memberValue":"1","memberAccepted":true,"mode":"sum","epoch":0,"value":"1"}`), nil
			default:
				return jsonLoopbackResponse(200, `{"key":"c","value":"1","epoch":0}`), nil
			}
		})},
		Batch: &BatchOptions{Interval: -1},
	})
	if err != nil {
		t.Fatal(err)
	}
	h, _ := c.Counter("c")
	m, _ := h.Member("alice")
	at := time.Date(2026, 7, 1, 0, 0, 0, 0, time.UTC)
	if _, err := h.AddNow(context.Background(), 1, WriteOptions{IdempotencyKey: "counter-add"}); err != nil {
		t.Fatal(err)
	}
	if _, err := h.SubtractNow(context.Background(), 1, WriteOptions{IdempotencyKey: "counter-subtract"}); err != nil {
		t.Fatal(err)
	}
	if _, err := h.AddNowAt(context.Background(), 1, at, WriteOptions{IdempotencyKey: "counter-add-at"}); err != nil {
		t.Fatal(err)
	}
	if _, err := h.SubtractNowAt(context.Background(), 1, at, WriteOptions{IdempotencyKey: "counter-subtract-at"}); err != nil {
		t.Fatal(err)
	}
	if _, err := h.Clear(context.Background(), WriteOptions{IdempotencyKey: "counter-clear"}); err != nil {
		t.Fatal(err)
	}
	if err := h.Delete(context.Background(), WriteOptions{IdempotencyKey: "counter-delete"}); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Add(context.Background(), 1, MemberWriteOpts{IdempotencyKey: "member-add"}); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Subtract(context.Background(), 1, MemberWriteOpts{IdempotencyKey: "member-subtract"}); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Submit(context.Background(), 1, SubmitOpts{Mode: "max", IdempotencyKey: "member-submit"}); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Remove(context.Background(), WriteOptions{IdempotencyKey: "member-remove"}); err != nil {
		t.Fatal(err)
	}
	want := []string{
		"counter-add", "counter-subtract", "counter-add-at", "counter-subtract-at", "counter-clear", "counter-delete",
		"member-add", "member-subtract", "member-submit", "member-remove",
	}
	if !slices.Equal(keys, want) {
		t.Fatalf("idempotency keys=%v, want %v", keys, want)
	}
}

func TestEveryProducibleErrorCategoryHasExactlyOneTaxonomyKind(t *testing.T) {
	assert := func(name string, err error, want string) {
		t.Helper()
		t.Run(name, func(t *testing.T) {
			assertExactlyOneTaxonomyKind(t, err, want)
		})
	}

	_, err := NewClient(Options{})
	assert("construction/missing-api-key", err, "validation")
	_, err = NewPublishableClient(PublishableOptions{})
	assert("construction/publishable-missing-api-key", err, "validation")
	_, err = NewClient(Options{APIKey: "k", BaseURL: "://not-a-url"})
	assert("construction/invalid-base-url", err, "validation")
	_, err = NewClient(Options{APIKey: "bad\nkey"})
	assert("construction/invalid-auth-header", err, "validation")
	_, err = NewClient(Options{APIKey: "k", MaxRetries: -2})
	assert("construction/invalid-max-retries", err, "validation")
	_, err = NewClient(Options{APIKey: "k", Backoff: -1})
	assert("construction/invalid-backoff", err, "validation")
	_, err = NewClient(Options{APIKey: "k", Batch: &BatchOptions{MaxBatchSize: -1}})
	assert("construction/invalid-batch-size", err, "validation")
	_, err = NewClient(Options{APIKey: "k", Batch: &BatchOptions{Interval: -2}})
	assert("construction/invalid-batch-interval", err, "validation")

	local, err := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			return nil, errors.New("validation unexpectedly issued a request")
		})},
		Batch: &BatchOptions{Interval: -1},
	})
	if err != nil {
		t.Fatal(err)
	}
	_, err = local.Counter("has space")
	assert("input/counter-key", err, "validation")
	_, err = local.Derived("has space")
	assert("input/derived-key", err, "validation")
	h, _ := local.Counter("c")
	_, err = h.Member("has space")
	assert("input/member-key", err, "validation")
	_, err = ToAmount(-1)
	assert("input/amount", err, "validation")
	var nilBigInt *big.Int
	_, err = ToAmount(nilBigInt)
	assert("input/typed-nil-amount", err, "validation")
	_, err = ToValue("1.5")
	assert("input/value", err, "validation")
	_, err = ToValue(nilBigInt)
	assert("input/typed-nil-value", err, "validation")
	m, _ := h.Member("alice")
	_, err = m.Add(context.Background(), 1, MemberWriteOpts{Metadata: strings.Repeat("x", metadataMaxBytes+1)})
	assert("input/metadata", err, "validation")
	_, err = h.Series(context.Background(), SeriesParams{Bucket: "2m"})
	assert("input/series-bucket", err, "validation")
	_, err = h.Series(context.Background(), SeriesParams{Bucket: "1h", Mode: "sum"})
	assert("input/series-mode", err, "validation")
	_, err = (&DerivedHandle{client: local, Key: "d"}).Series(context.Background(), DerivedSeriesParams{Bucket: "2m"})
	assert("input/derived-series-bucket", err, "validation")
	_, err = h.WindowLeaderboard(context.Background(), WindowLeaderboardParams{Window: "2h"})
	assert("input/window", err, "validation")
	_, err = m.Add(context.Background(), 1, MemberWriteOpts{}, MemberWriteOpts{})
	assert("input/multiple-member-options", err, "validation")
	_, err = h.AddNow(context.Background(), 1, WriteOptions{}, WriteOptions{})
	assert("input/multiple-write-options", err, "validation")
	_, err = h.AddNow(context.Background(), 1, WriteOptions{IdempotencyKey: strings.Repeat("k", idempotencyKeyMaxLength+1)})
	assert("input/overlong-idempotency-key", err, "validation")
	_, err = h.AddNow(context.Background(), 1, WriteOptions{IdempotencyKey: "bad\nkey"})
	assert("input/invalid-idempotency-header", err, "validation")
	originalReader := idempotencyReader
	idempotencyReader = errorReader{err: errors.New("entropy unavailable")}
	_, err = h.AddNow(context.Background(), 1)
	idempotencyReader = originalReader
	assert("local/idempotency-generation", err, "transport")
	_, err = h.Value(nil)
	assert("input/nil-context", err, "validation")
	err = local.do(context.Background(), "POST", "/test", make(chan int), "", nil, nil)
	assert("request/body-encoding", err, "validation")

	badRequestClient, _ := NewClient(Options{APIKey: "k", Batch: &BatchOptions{Interval: -1}})
	badRequestClient.baseURL = "http://[::1"
	badRequestHandle, _ := badRequestClient.Counter("c")
	_, err = badRequestHandle.Value(context.Background())
	assert("request/construction", err, "validation")

	closed, _ := NewClient(Options{APIKey: "k", Batch: &BatchOptions{Interval: -1}})
	closedHandle, _ := closed.Counter("c")
	if err := closed.Close(); err != nil {
		t.Fatal(err)
	}
	err = closedHandle.Add(1)
	if !errors.Is(err, ErrClientClosed) {
		t.Fatalf("closed error %v no longer matches ErrClientClosed", err)
	}
	assert("lifecycle/client-closed", err, "validation")

	requestError := func(response *http.Response, transportErr error) error {
		client, clientErr := NewClient(Options{
			APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
			HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
				return response, transportErr
			})},
			Batch: &BatchOptions{Interval: -1},
		})
		if clientErr != nil {
			t.Fatal(clientErr)
		}
		handle, _ := client.Counter("c")
		_, callErr := handle.Value(context.Background())
		return callErr
	}
	assert("response/http-error", requestError(jsonLoopbackResponse(404, `{"title":"missing"}`), nil), "api")
	assert("response/unparseable-2xx", requestError(jsonLoopbackResponse(200, "{bad"), nil), "api")
	bodyReadError := jsonLoopbackResponse(200, "")
	bodyReadError.Body = io.NopCloser(errorReader{err: errors.New("body read failed")})
	assert("response/body-read-error", requestError(bodyReadError, nil), "api")
	assert("transport/no-response", requestError(nil, errors.New("network down")), "transport")
	assert("transport/round-tripper-nil-response-and-error", requestError(nil, nil), "transport")
	assert("transport/round-tripper-invalid-nil-body", requestError(&http.Response{
		StatusCode: 200, ContentLength: 1,
	}, nil), "transport")
	assert("transport/round-tripper-invalid-status", requestError(&http.Response{
		StatusCode: 0, Body: io.NopCloser(strings.NewReader(`{}`)),
	}, nil), "transport")
	redirectResponse := jsonLoopbackResponse(302, "")
	redirectResponse.Header.Set("Location", "https://unit.test/redirected")
	redirectClient, _ := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
		HTTPClient: &http.Client{
			Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
				return redirectResponse, nil
			}),
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return errors.New("redirect rejected")
			},
		},
		Batch: &BatchOptions{Interval: -1},
	})
	redirectHandle, _ := redirectClient.Counter("c")
	_, err = redirectHandle.Value(context.Background())
	assert("response/redirect-policy-error", err, "api")

	attempts := 0
	mixed, _ := NewClient(Options{
		APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: 1,
		HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
			attempts++
			if attempts == 1 {
				return jsonLoopbackResponse(503, `{"title":"unavailable"}`), nil
			}
			return nil, errors.New("network then failed")
		})},
		Batch: &BatchOptions{Interval: -1},
	})
	mixed.sleepFn = func(time.Duration) {}
	mixedHandle, _ := mixed.Counter("c")
	_, err = mixedHandle.Value(context.Background())
	assert("response/http-then-no-response", err, "api")

	for _, tc := range []struct {
		name string
		body string
		want string
	}{
		{"response/batch-problem-with-status", `{"results":[{"counterKey":"c","status":"error","error":{"title":"quota","status":403}}]}`, "api"},
		{"response/batch-problem-without-status", `{"results":[{"counterKey":"c","status":"error","error":{"title":"quota"}}]}`, "validation"},
		{"response/batch-problem-invalid-status", `{"results":[{"counterKey":"c","status":"error","error":{"title":"quota","status":600}}]}`, "validation"},
	} {
		batchClient, batchErr := NewClient(Options{
			APIKey: "k", BaseURL: "https://unit.test/v1", MaxRetries: -1,
			HTTPClient: &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
				return jsonLoopbackResponse(200, tc.body), nil
			})},
			Batch: &BatchOptions{Interval: -1},
		})
		if batchErr != nil {
			t.Fatal(batchErr)
		}
		batchHandle, _ := batchClient.Counter("c")
		_ = batchHandle.Add(1)
		assert(tc.name, batchClient.Flush(), tc.want)
	}
}

func assertExactlyOneTaxonomyKind(t *testing.T, err error, want string) {
	t.Helper()
	if err == nil {
		t.Fatal("expected an error")
	}
	var root Error
	if !errors.As(err, &root) {
		t.Fatalf("%T is not caught by counters.Error: %v", err, err)
	}
	matched := make([]string, 0, 3)
	var apiErr *APIError
	if errors.As(err, &apiErr) {
		matched = append(matched, "api")
	}
	var transportErr *TransportError
	if errors.As(err, &transportErr) {
		matched = append(matched, "transport")
	}
	var validationErr *ValidationError
	if errors.As(err, &validationErr) {
		matched = append(matched, "validation")
	}
	if len(matched) != 1 || matched[0] != want {
		t.Fatalf("%T matched taxonomy kinds %v, want exactly [%s]: %v", err, matched, want, err)
	}
}

type errorReader struct{ err error }

func (r errorReader) Read([]byte) (int, error) { return 0, r.err }

func TestNewIdempotencyKeyFailsClosedWithTypedTransportPanic(t *testing.T) {
	originalReader := idempotencyReader
	idempotencyReader = errorReader{err: errors.New("entropy unavailable")}
	defer func() { idempotencyReader = originalReader }()
	defer func() {
		recovered := recover()
		err, ok := recovered.(error)
		if !ok {
			t.Fatalf("panic=%T %v, want an error", recovered, recovered)
		}
		assertExactlyOneTaxonomyKind(t, err, "transport")
	}()

	NewIdempotencyKey()
	t.Fatal("NewIdempotencyKey returned a partial key instead of failing closed")
}
