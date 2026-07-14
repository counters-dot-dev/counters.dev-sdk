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
		_ = json.NewEncoder(w).Encode(map[string]any{"results": []any{}})
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
	if u.Month != "2026-07" || u.Ops.Used != 42 || u.Ops.Quota != nil || u.Limits.MonthlyOpsQuota != nil || u.Ops.ResetsAt == "" {
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

	added, err := m.Add(context.Background(), huge, MemberWriteOpts{Metadata: "room1:500", OccurredAt: at})
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
		{"all params", SeriesParams{From: from, To: to, Bucket: "1h", Mode: "delta", Tz: "Europe/Amsterdam", Gapfill: true},
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
			if len(s.Points) != 2 || s.Points[0].V != "1" || s.Points[1].V != "100000000000000000000000000000000" {
				t.Errorf("points=%+v", s.Points)
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
				params.Tz = tz
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
				_, _ = w.Write([]byte(`{"counterKey":"c","bucket":"` + params.Bucket + `","mode":"delta","range":{"from":"","to":""},"points":[]}`))
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
				assertSeriesPoints(t, s.Points, exp["points"].([]any))
			case "memberGroupSeries":
				s, err := h.GroupSeries(context.Background(), params)
				if err != nil {
					t.Fatal(err)
				}
				if s.CounterKey != exp["counterKey"].(string) || s.Bucket != exp["bucket"].(string) {
					t.Errorf("member group series=%+v", s)
				}
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
				assertSeriesPoints(t, s.Points, exp["points"].([]any))
			}
		})
	}
}

func assertSeriesPoints(t *testing.T, got []SeriesPoint, want []any) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("points len=%d, want %d", len(got), len(want))
	}
	for i, ep := range want {
		epm := ep.(map[string]any)
		if got[i].T != epm["t"].(string) || got[i].V != epm["v"].(string) {
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
					return jsonLoopbackResponse(200, `{"key":"k","mode":"sum","window":"`+w+`","order":"desc","total":"0","memberCount":0,"limit":100,"offset":0,"effectiveStart":"","effectiveEnd":"","entries":[]}`), nil
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
		opts.OccurredAt = parsed
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

func assertWindowLeaderboard(t *testing.T, got *WindowLeaderboard, exp map[string]any) {
	t.Helper()
	if got.Key != exp["key"].(string) || got.Mode != exp["mode"].(string) || got.Window != exp["window"].(string) || got.Order != exp["order"].(string) || got.Total != exp["total"].(string) {
		t.Errorf("window leaderboard=%+v, expect=%v", got, exp)
	}
	if got.EffectiveStart != exp["effectiveStart"].(string) || got.EffectiveEnd != exp["effectiveEnd"].(string) {
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
				return jsonLoopbackResponse(200, `{"key":"conversion","bucket":"`+params["bucket"].(string)+`","scale":6,"range":{"from":"","to":""},"points":[]}`), nil
			})
			d, err := cl.Derived("conversion")
			if err != nil {
				t.Fatal(err)
			}
			from, _ := time.Parse(time.RFC3339, params["from"].(string))
			to, _ := time.Parse(time.RFC3339, params["to"].(string))
			_, err = d.Series(context.Background(), DerivedSeriesParams{
				From:   from,
				To:     to,
				Bucket: params["bucket"].(string),
				Tz:     stringFromAny(params["tz"]),
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
				from, _ := time.Parse(time.RFC3339, body["range"].(map[string]any)["from"].(string))
				to, _ := time.Parse(time.RFC3339, body["range"].(map[string]any)["to"].(string))
				got, err := d.Series(context.Background(), DerivedSeriesParams{From: from, To: to, Bucket: body["bucket"].(string)})
				if err != nil {
					t.Fatal(err)
				}
				if got.Key != exp["key"].(string) || got.Bucket != exp["bucket"].(string) || got.Scale != intFromAny(exp["scale"]) {
					t.Errorf("derived series=%+v, expect=%v", got, exp)
				}
				wantPoints := exp["points"].([]any)
				if len(got.Points) != len(wantPoints) {
					t.Fatalf("points len=%d, want %d", len(got.Points), len(wantPoints))
				}
				for i, rawPoint := range wantPoints {
					wantPoint := rawPoint.(map[string]any)
					if got.Points[i].T != wantPoint["t"].(string) {
						t.Errorf("point %d t=%q, want %q", i, got.Points[i].T, wantPoint["t"])
					}
					if wantPoint["v"] == nil {
						if got.Points[i].V != nil {
							t.Errorf("point %d v=%q, want nil", i, *got.Points[i].V)
						}
					} else if got.Points[i].V == nil || *got.Points[i].V != wantPoint["v"].(string) {
						t.Errorf("point %d v=%v, want %q", i, got.Points[i].V, wantPoint["v"])
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
		_, _ = w.Write([]byte(`{"results":[]}`))
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
			cl, _ := NewClient(Options{APIKey: "k", BaseURL: base + "/v1", MaxRetries: 0, Backoff: 0})
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
			h, _ := cl.Counter("a")
			if err := h.Add(1); err != nil {
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

func TestImmediateModeRoutesErrorsToOnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(403)
		_, _ = io.WriteString(w, `{"title":"quota exceeded","status":403}`)
	}))
	defer srv.Close()

	errCh := make(chan error, 1)
	c, _ := NewClient(Options{
		APIKey: "k", BaseURL: srv.URL + "/v1", MaxRetries: -1,
		Batch: &BatchOptions{Disabled: true, OnError: func(err error) { errCh <- err }},
	})
	h, _ := c.Counter("c")
	if err := h.Add(1); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-errCh:
		var apiErr *APIError
		if !errors.As(err, &apiErr) || apiErr.Status != 403 {
			t.Fatalf("OnError got %v, want *APIError with status 403", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("immediate-mode write failure never reached OnError")
	}
}

func TestImmediateModeRejectsWritesAfterClose(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"results":[]}`)
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
