// Package counters is the official Go SDK for the Counters arbitrary-precision counter service.
//
// Amounts and values are arbitrary precision: inputs accept int, int64, string, or *big.Int and are sent over the
// wire as decimal strings (never JSON numbers). Validation mirrors the server and is checked against the shared
// conformance vectors.
package counters

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const defaultBaseURL = "https://api.counters.dev/v1"

var (
	counterKeyRe = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,200}$`)
	memberKeyRe  = regexp.MustCompile(`^[A-Za-z0-9._:@|-]{1,256}$`)
	amountRe     = regexp.MustCompile(`^[0-9]+$`)
	valueRe      = regexp.MustCompile(`^-?[0-9]+$`)
)

const metadataMaxBytes = 1024

// Error is the marker interface implemented by every error this SDK originates: *ValidationError,
// *APIError, and *TransportError. It follows the net.Error pattern (an error plus an unexported
// method) so callers can catch "anything from this SDK" with a single errors.As:
//
//	var cerr counters.Error
//	if errors.As(err, &cerr) { /* a Counters SDK failure */ }
type Error interface {
	error
	isCountersError()
}

// ValidationError is returned for client-side validation failures (bad counter key or amount).
type ValidationError struct{ Msg string }

func (e *ValidationError) Error() string    { return e.Msg }
func (e *ValidationError) isCountersError() {}

// APIError is returned for a terminal HTTP error response (or a 2xx whose body could not be parsed).
// Status is always the HTTP status of that response; a transport failure that never produced a
// response is a *TransportError, not an APIError with Status 0.
type APIError struct {
	Status int
	Title  string
}

func (e *APIError) Error() string    { return fmt.Sprintf("counters: HTTP %d: %s", e.Status, e.Title) }
func (e *APIError) isCountersError() {}

// TransportError is returned when no HTTP response was obtained: a network error that persisted
// until retries were exhausted. It carries no HTTP status. Cause is the last underlying failure.
type TransportError struct {
	Cause error
}

func (e *TransportError) Error() string {
	if e.Cause != nil {
		return "counters: transport error: " + e.Cause.Error()
	}
	return "counters: transport error"
}
func (e *TransportError) Unwrap() error    { return e.Cause }
func (e *TransportError) isCountersError() {}

// ErrClientClosed is returned by Add/Subtract when a write is attempted after Close(); the write is
// rejected rather than silently stranded in a buffer whose worker has already stopped.
var ErrClientClosed = errors.New("counters: client is closed")

// IsValidCounterKey reports whether key matches the server's allowed shape.
func IsValidCounterKey(key string) bool { return counterKeyRe.MatchString(key) }

// IsValidMemberKey reports whether member matches the server's member-key shape.
func IsValidMemberKey(member string) bool { return memberKeyRe.MatchString(member) }

func validateMemberKey(member string) error {
	if !IsValidMemberKey(member) {
		return &ValidationError{"invalid member key: " + member}
	}
	return nil
}

// MetadataByteLength returns the UTF-8 byte length used by the metadata cap.
func MetadataByteLength(metadata string) int { return len([]byte(metadata)) }

// IsValidMetadata reports whether metadata is within the server's 1024-byte UTF-8 cap.
func IsValidMetadata(metadata string) bool { return MetadataByteLength(metadata) <= metadataMaxBytes }

func validateMetadata(metadata string) error {
	bytes := MetadataByteLength(metadata)
	if bytes > metadataMaxBytes {
		return &ValidationError{fmt.Sprintf("metadata exceeds %d UTF-8 bytes (got %d)", metadataMaxBytes, bytes)}
	}
	return nil
}

// Buckets is the fixed set of series bucket sizes the API accepts (openapi.yaml
// SeriesParams.bucket enum, conformance/buckets.json). Finer buckets may still be rejected
// server-side by plan; that is a separate, non-local concern.
var Buckets = []string{"1m", "5m", "1h", "1d", "1w", "1mo"}

// IsValidBucket reports whether bucket is one of the allowed series bucket sizes.
func IsValidBucket(bucket string) bool {
	for _, b := range Buckets {
		if b == bucket {
			return true
		}
	}
	return false
}

// Windows is the fixed set of trailing-window sizes accepted by leaderboard reads.
var Windows = []string{"1h", "6h", "12h", "1d", "7d", "30d"}

// IsValidWindow reports whether window is one of the allowed trailing leaderboard windows.
func IsValidWindow(window string) bool {
	for _, w := range Windows {
		if w == window {
			return true
		}
	}
	return false
}

func validateWindow(window string) error {
	if !IsValidWindow(window) {
		return &ValidationError{"invalid window " + strconv.Quote(window) + "; expected one of " + strings.Join(Windows, ", ")}
	}
	return nil
}

// ToAmount normalises an amount input to a non-negative *big.Int.
func ToAmount(v any) (*big.Int, error) {
	switch x := v.(type) {
	case *big.Int:
		if x.Sign() < 0 {
			return nil, &ValidationError{"amount must be non-negative"}
		}
		return new(big.Int).Set(x), nil
	case int:
		return ToAmount(int64(x))
	case int64:
		if x < 0 {
			return nil, &ValidationError{"amount must be non-negative"}
		}
		return big.NewInt(x), nil
	case string:
		if !amountRe.MatchString(x) {
			return nil, &ValidationError{"amount must be a non-negative integer: " + x}
		}
		n, ok := new(big.Int).SetString(x, 10)
		if !ok {
			return nil, &ValidationError{"amount must be a non-negative integer: " + x}
		}
		return n, nil
	default:
		return nil, &ValidationError{fmt.Sprintf("unsupported amount type %T", v)}
	}
}

// ToValue normalises a signed integer input to a *big.Int.
func ToValue(v any) (*big.Int, error) {
	switch x := v.(type) {
	case *big.Int:
		return new(big.Int).Set(x), nil
	case int:
		return big.NewInt(int64(x)), nil
	case int64:
		return big.NewInt(x), nil
	case string:
		if !valueRe.MatchString(x) {
			return nil, &ValidationError{"value must be a signed integer: " + x}
		}
		n, ok := new(big.Int).SetString(x, 10)
		if !ok {
			return nil, &ValidationError{"value must be a signed integer: " + x}
		}
		return n, nil
	default:
		return nil, &ValidationError{fmt.Sprintf("unsupported value type %T", v)}
	}
}

// NewIdempotencyKey returns a random v4-style UUID string.
func NewIdempotencyKey() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// --- wire types (mirror openapi/openapi.yaml) ---

type Counter struct {
	Key   string `json:"key"`
	Value string `json:"value"`
	Epoch int64  `json:"epoch"`
	// CreatedAt/UpdatedAt are optional in the API (spec: date-time), so they are pointers: nil when
	// the server omits them. time.Time parses the RFC 3339 wire format.
	CreatedAt *time.Time `json:"createdAt,omitempty"`
	UpdatedAt *time.Time `json:"updatedAt,omitempty"`
}

type ValueResponse struct {
	Key   string `json:"key"`
	Value string `json:"value"`
	Epoch int64  `json:"epoch"`
}

type CounterPage struct {
	Data       []Counter `json:"data"`
	NextCursor string    `json:"nextCursor,omitempty"`
}

type SeriesPoint struct {
	T string `json:"t"`
	V string `json:"v"`
}

type SeriesResponse struct {
	CounterKey string `json:"counterKey"`
	Bucket     string `json:"bucket"`
	Mode       string `json:"mode"`
	Tz         string `json:"tz"`
	Range      struct {
		From string `json:"from"`
		To   string `json:"to"`
	} `json:"range"`
	Points []SeriesPoint `json:"points"`
}

type SeriesParams struct {
	From    time.Time
	To      time.Time
	Bucket  string
	Mode    string
	Tz      string
	Gapfill bool
}

type Usage struct {
	Month string `json:"month"`
	Ops   struct {
		Used     int64  `json:"used"`
		Quota    *int64 `json:"quota"`
		ResetsAt string `json:"resetsAt"`
	} `json:"ops"`
	Counters struct {
		Used int64 `json:"used"`
		Max  int64 `json:"max"`
	} `json:"counters"`
	Limits struct {
		RateLimitRps    int64  `json:"rateLimitRps"`
		MaxCounters     int64  `json:"maxCounters"`
		MonthlyOpsQuota *int64 `json:"monthlyOpsQuota"`
	} `json:"limits"`
}

type LeaderboardParams struct {
	Limit  int
	Offset int
	Order  string
	Epoch  *int64
}

type WindowLeaderboardParams struct {
	Limit  int
	Offset int
	Order  string
	Epoch  *int64
	Window string
}

type LeaderboardEntry struct {
	Rank      int     `json:"rank"`
	Member    string  `json:"member"`
	Value     string  `json:"value"`
	Metadata  *string `json:"metadata,omitempty"`
	UpdatedAt string  `json:"updatedAt"`
}

type Leaderboard struct {
	Key         string             `json:"key"`
	Mode        string             `json:"mode"`
	Epoch       int64              `json:"epoch"`
	Order       string             `json:"order"`
	Total       *string            `json:"total,omitempty"`
	MemberCount int                `json:"memberCount"`
	Limit       int                `json:"limit"`
	Offset      int                `json:"offset"`
	Entries     []LeaderboardEntry `json:"entries"`
}

type WindowEntry struct {
	Rank   int    `json:"rank"`
	Member string `json:"member"`
	Value  string `json:"value"`
}

type WindowLeaderboard struct {
	Key            string        `json:"key"`
	Mode           string        `json:"mode"`
	Window         string        `json:"window"`
	Order          string        `json:"order"`
	Total          string        `json:"total"`
	MemberCount    int           `json:"memberCount"`
	Limit          int           `json:"limit"`
	Offset         int           `json:"offset"`
	EffectiveStart string        `json:"effectiveStart"`
	EffectiveEnd   string        `json:"effectiveEnd"`
	Entries        []WindowEntry `json:"entries"`
}

type MemberWriteOpts struct {
	Metadata   string
	OccurredAt time.Time
}

type SubmitOpts struct {
	Mode       string
	Metadata   string
	OccurredAt time.Time
}

type MemberGetParams struct {
	Epoch *int64
	Order string
}

type MemberValue struct {
	Key            string  `json:"key"`
	Member         string  `json:"member"`
	MemberValue    string  `json:"memberValue"`
	MemberAccepted bool    `json:"memberAccepted"`
	Mode           string  `json:"mode"`
	Epoch          int64   `json:"epoch"`
	Value          *string `json:"value,omitempty"`
}

type MemberRemoved struct {
	Key    string  `json:"key"`
	Member string  `json:"member"`
	Epoch  int64   `json:"epoch"`
	Value  *string `json:"value,omitempty"`
}

type MemberSnapshot struct {
	Key         string  `json:"key"`
	Member      string  `json:"member"`
	Value       string  `json:"value"`
	Metadata    *string `json:"metadata,omitempty"`
	Rank        int     `json:"rank"`
	Percentile  string  `json:"percentile"`
	MemberCount int     `json:"memberCount"`
	Mode        string  `json:"mode"`
	Epoch       int64   `json:"epoch"`
	UpdatedAt   string  `json:"updatedAt"`
}

type MemberSeriesResponse struct {
	CounterKey string `json:"counterKey"`
	Member     string `json:"member"`
	Bucket     string `json:"bucket"`
	Mode       string `json:"mode"`
	Tz         string `json:"tz"`
	Range      struct {
		From string `json:"from"`
		To   string `json:"to"`
	} `json:"range"`
	Points []SeriesPoint `json:"points"`
}

type MemberSeriesEntry struct {
	Member string        `json:"member"`
	Points []SeriesPoint `json:"points"`
}

type MemberGroupSeriesResponse struct {
	CounterKey string `json:"counterKey"`
	Bucket     string `json:"bucket"`
	Tz         string `json:"tz"`
	Range      struct {
		From string `json:"from"`
		To   string `json:"to"`
	} `json:"range"`
	Series []MemberSeriesEntry `json:"series"`
}

type DerivedSeriesParams struct {
	From   time.Time
	To     time.Time
	Bucket string
	Tz     string
}

type DerivedValueResponse struct {
	Key    string            `json:"key"`
	Value  *string           `json:"value"`
	Scale  int               `json:"scale"`
	Inputs map[string]string `json:"inputs"`
	Reason *string           `json:"reason,omitempty"`
}

type DerivedSeriesPoint struct {
	T string  `json:"t"`
	V *string `json:"v"`
}

type DerivedSeriesResponse struct {
	Key    string `json:"key"`
	Bucket string `json:"bucket"`
	Tz     string `json:"tz"`
	Scale  int    `json:"scale"`
	Range  struct {
		From string `json:"from"`
		To   string `json:"to"`
	} `json:"range"`
	Points []DerivedSeriesPoint `json:"points"`
}

// Operation is one entry in a batch.
type Operation struct {
	CounterKey     string `json:"counterKey"`
	Op             string `json:"op"`
	Amount         string `json:"amount,omitempty"`
	IdempotencyKey string `json:"idempotencyKey,omitempty"`
	// OccurredAt (RFC 3339) buckets the op at event time instead of ingest time (offline spools).
	OccurredAt string `json:"occurredAt,omitempty"`
}

// --- client ---

// Options configures a Client. Only APIKey is required; every zero value means "use the default".
type Options struct {
	// APIKey is the organization API key, sent as "Authorization: Bearer <key>". Required.
	APIKey string
	// BaseURL overrides the production endpoint (default https://api.counters.dev/v1).
	BaseURL string
	// HTTPClient overrides the transport (default: net/http client with a 30s overall timeout).
	HTTPClient *http.Client
	// MaxRetries is the number of retries after the first attempt on connect errors and
	// HTTP 429/5xx (default 3). Set -1 to disable retries entirely.
	MaxRetries int
	// Backoff is the base delay between retries, doubled per attempt (default 200ms). A server
	// Retry-After header, when present, takes precedence.
	Backoff time.Duration
	// Batch tunes the client-side write buffer; nil keeps every default (buffering enabled).
	Batch *BatchOptions
}

// BatchOptions tunes the buffering of CounterHandle.Add/Subtract writes.
type BatchOptions struct {
	// Disabled turns buffering off: each Add/Subtract fires one immediate batch call instead.
	Disabled bool
	// MaxBatchSize is the buffered distinct-counter count that triggers an early flush (default 100).
	MaxBatchSize int
	// Interval is the background flush cadence (default 1s). <= 0 disables the timer; flush
	// manually with Client.Flush or rely on MaxBatchSize.
	Interval time.Duration
	// OnError receives errors from fire-and-forget writes — background flushes and, when Disabled
	// is true, immediate-mode writes. These run detached from any caller, so without this hook
	// they are silent.
	OnError func(error)
}

type Client struct {
	apiKey       string
	baseURL      string
	httpClient   *http.Client
	maxRetries   int
	backoff      time.Duration
	batchEnabled bool
	batcher      *batcher
	onWriteError func(error)         // BatchOptions.OnError; also the sink for immediate-mode write failures
	sleepFn      func(time.Duration) // nil => time.Sleep; overridden in tests to record backoff
}

func NewClient(opts Options) (*Client, error) {
	if opts.APIKey == "" {
		return nil, errors.New("counters: APIKey is required")
	}
	maxRetries := opts.MaxRetries
	switch {
	case maxRetries == 0:
		maxRetries = 3 // zero value => default
	case maxRetries < 0:
		maxRetries = 0 // -1 => retries disabled
	}
	c := &Client{
		apiKey:     opts.APIKey,
		baseURL:    orString(opts.BaseURL, defaultBaseURL),
		httpClient: orHTTP(opts.HTTPClient),
		maxRetries: maxRetries,
		backoff:    orDur(opts.Backoff, 200*time.Millisecond),
	}
	enabled := true
	maxSize := 100
	interval := time.Second
	var onErr func(error)
	if opts.Batch != nil {
		enabled = !opts.Batch.Disabled
		maxSize = orInt(opts.Batch.MaxBatchSize, 100)
		interval = orDur(opts.Batch.Interval, time.Second)
		onErr = opts.Batch.OnError
	}
	c.batchEnabled = enabled
	c.onWriteError = onErr
	c.batcher = newBatcher(func(ops []Operation) error {
		return c.submitBatch(context.Background(), ops)
	}, maxSize, interval, onErr)
	return c, nil
}

// CounterHandle is a typed handle to a single counter.
type CounterHandle struct {
	client *Client
	Key    string
}

// Counter returns a handle, validating the key.
func (c *Client) Counter(key string) (*CounterHandle, error) {
	if !IsValidCounterKey(key) {
		return nil, &ValidationError{"invalid counter key: " + key}
	}
	return &CounterHandle{client: c, Key: key}, nil
}

// Derived returns a handle for a server-defined derived counter, validating the key.
func (c *Client) Derived(key string) (*DerivedHandle, error) {
	if !IsValidCounterKey(key) {
		return nil, &ValidationError{"invalid derived key: " + key}
	}
	return &DerivedHandle{client: c, Key: key}, nil
}

// Add buffers an increment (flushed in the background; coalesced per counter).
func (h *CounterHandle) Add(amount any) error {
	n, err := ToAmount(amount)
	if err != nil {
		return err
	}
	return h.client.enqueue(h.Key, n)
}

// Subtract buffers a decrement. The counter may go negative.
func (h *CounterHandle) Subtract(amount any) error {
	n, err := ToAmount(amount)
	if err != nil {
		return err
	}
	return h.client.enqueue(h.Key, new(big.Int).Neg(n))
}

func (h *CounterHandle) AddNow(ctx context.Context, amount any) (*Counter, error) {
	return h.applyNow(ctx, "add", amount, time.Time{})
}

func (h *CounterHandle) SubtractNow(ctx context.Context, amount any) (*Counter, error) {
	return h.applyNow(ctx, "subtract", amount, time.Time{})
}

// AddNowAt applies an increment stamped with an event time (series bucket lands at occurredAt;
// bounded server-side to the plan's retention window). For offline spools flushing late.
func (h *CounterHandle) AddNowAt(ctx context.Context, amount any, occurredAt time.Time) (*Counter, error) {
	return h.applyNow(ctx, "add", amount, occurredAt)
}

// SubtractNowAt is AddNowAt for decrements.
func (h *CounterHandle) SubtractNowAt(ctx context.Context, amount any, occurredAt time.Time) (*Counter, error) {
	return h.applyNow(ctx, "subtract", amount, occurredAt)
}

func (h *CounterHandle) applyNow(ctx context.Context, op string, amount any, occurredAt time.Time) (*Counter, error) {
	n, err := ToAmount(amount)
	if err != nil {
		return nil, err
	}
	body := map[string]string{"amount": n.String()}
	if !occurredAt.IsZero() {
		body["occurredAt"] = occurredAt.UTC().Format(time.RFC3339)
	}
	var out Counter
	err = h.client.do(ctx, "POST", "/counters/"+url.PathEscape(h.Key)+"/"+op,
		body, NewIdempotencyKey(), nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (h *CounterHandle) Clear(ctx context.Context) (*Counter, error) {
	var out Counter
	err := h.client.do(ctx, "POST", "/counters/"+url.PathEscape(h.Key)+"/clear", nil, NewIdempotencyKey(), nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (h *CounterHandle) Delete(ctx context.Context) error {
	return h.client.do(ctx, "DELETE", "/counters/"+url.PathEscape(h.Key), nil, NewIdempotencyKey(), nil, nil)
}

func (h *CounterHandle) Value(ctx context.Context) (*ValueResponse, error) {
	var out ValueResponse
	err := h.client.do(ctx, "GET", "/counters/"+url.PathEscape(h.Key)+"/value", nil, "", nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (h *CounterHandle) Series(ctx context.Context, p SeriesParams) (*SeriesResponse, error) {
	q, err := seriesQuery(p)
	if err != nil {
		return nil, err
	}
	var out SeriesResponse
	err = h.client.do(ctx, "GET", "/counters/"+url.PathEscape(h.Key)+"/series", nil, "", q, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (h *CounterHandle) MemberSeries(ctx context.Context, member string, p SeriesParams) (*MemberSeriesResponse, error) {
	if err := validateMemberKey(member); err != nil {
		return nil, err
	}
	q, err := seriesQuery(p)
	if err != nil {
		return nil, err
	}
	q.Set("member", member)
	var out MemberSeriesResponse
	err = h.client.do(ctx, "GET", "/counters/"+url.PathEscape(h.Key)+"/series", nil, "", q, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (h *CounterHandle) GroupSeries(ctx context.Context, p SeriesParams) (*MemberGroupSeriesResponse, error) {
	q, err := seriesQuery(p)
	if err != nil {
		return nil, err
	}
	q.Set("groupBy", "member")
	var out MemberGroupSeriesResponse
	err = h.client.do(ctx, "GET", "/counters/"+url.PathEscape(h.Key)+"/series", nil, "", q, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (h *CounterHandle) Leaderboard(ctx context.Context, p LeaderboardParams) (*Leaderboard, error) {
	var out Leaderboard
	err := h.client.do(ctx, "GET", "/counters/"+url.PathEscape(h.Key)+"/leaderboard", nil, "", leaderboardQuery(p), &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (h *CounterHandle) WindowLeaderboard(ctx context.Context, p WindowLeaderboardParams) (*WindowLeaderboard, error) {
	if err := validateWindow(p.Window); err != nil {
		return nil, err
	}
	q := leaderboardQuery(LeaderboardParams{Limit: p.Limit, Offset: p.Offset, Order: p.Order, Epoch: p.Epoch})
	q.Set("window", p.Window)
	var out WindowLeaderboard
	err := h.client.do(ctx, "GET", "/counters/"+url.PathEscape(h.Key)+"/leaderboard", nil, "", q, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

// Member returns a typed handle to one leaderboard member, validating the member key.
func (h *CounterHandle) Member(member string) (*MemberHandle, error) {
	if err := validateMemberKey(member); err != nil {
		return nil, err
	}
	return &MemberHandle{client: h.client, CounterKey: h.Key, Member: member}, nil
}

// MemberHandle is a typed handle to one member of a counter's leaderboard.
type MemberHandle struct {
	client     *Client
	CounterKey string
	Member     string
}

func (m *MemberHandle) Get(ctx context.Context, p MemberGetParams) (*MemberSnapshot, error) {
	q := url.Values{}
	if p.Epoch != nil {
		q.Set("epoch", strconv.FormatInt(*p.Epoch, 10))
	}
	if p.Order != "" {
		q.Set("order", p.Order)
	}
	var out MemberSnapshot
	err := m.client.do(ctx, "GET", "/counters/"+url.PathEscape(m.CounterKey)+"/members/"+url.PathEscape(m.Member), nil, "", q, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (m *MemberHandle) Remove(ctx context.Context) (*MemberRemoved, error) {
	var out MemberRemoved
	err := m.client.do(ctx, "DELETE", "/counters/"+url.PathEscape(m.CounterKey)+"/members/"+url.PathEscape(m.Member), nil, NewIdempotencyKey(), nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (m *MemberHandle) Add(ctx context.Context, amount any, opts ...MemberWriteOpts) (*MemberValue, error) {
	return m.applyDelta(ctx, "add", amount, opts...)
}

func (m *MemberHandle) Subtract(ctx context.Context, amount any, opts ...MemberWriteOpts) (*MemberValue, error) {
	return m.applyDelta(ctx, "subtract", amount, opts...)
}

func (m *MemberHandle) Submit(ctx context.Context, value any, opts SubmitOpts) (*MemberValue, error) {
	n, err := ToValue(value)
	if err != nil {
		return nil, err
	}
	body := map[string]string{"value": n.String()}
	if opts.Mode != "" {
		body["mode"] = opts.Mode
	}
	if opts.Metadata != "" {
		if err := validateMetadata(opts.Metadata); err != nil {
			return nil, err
		}
		body["metadata"] = opts.Metadata
	}
	if !opts.OccurredAt.IsZero() {
		body["occurredAt"] = opts.OccurredAt.UTC().Format(time.RFC3339)
	}
	var out MemberValue
	err = m.client.do(ctx, "POST", "/counters/"+url.PathEscape(m.CounterKey)+"/members/"+url.PathEscape(m.Member)+"/submit", body, NewIdempotencyKey(), nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (m *MemberHandle) applyDelta(ctx context.Context, op string, amount any, opts ...MemberWriteOpts) (*MemberValue, error) {
	n, err := ToAmount(amount)
	if err != nil {
		return nil, err
	}
	o, err := singleMemberWriteOpts(opts)
	if err != nil {
		return nil, err
	}
	body := map[string]string{"amount": n.String()}
	if o.Metadata != "" {
		if err := validateMetadata(o.Metadata); err != nil {
			return nil, err
		}
		body["metadata"] = o.Metadata
	}
	if !o.OccurredAt.IsZero() {
		body["occurredAt"] = o.OccurredAt.UTC().Format(time.RFC3339)
	}
	var out MemberValue
	err = m.client.do(ctx, "POST", "/counters/"+url.PathEscape(m.CounterKey)+"/members/"+url.PathEscape(m.Member)+"/"+op, body, NewIdempotencyKey(), nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

// DerivedHandle is a typed handle to a server-defined derived counter.
type DerivedHandle struct {
	client *Client
	Key    string
}

func (d *DerivedHandle) Value(ctx context.Context) (*DerivedValueResponse, error) {
	var out DerivedValueResponse
	err := d.client.do(ctx, "GET", "/derived/"+url.PathEscape(d.Key)+"/value", nil, "", nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (d *DerivedHandle) Series(ctx context.Context, p DerivedSeriesParams) (*DerivedSeriesResponse, error) {
	if !IsValidBucket(p.Bucket) {
		return nil, &ValidationError{"invalid bucket " + strconv.Quote(p.Bucket) + "; expected one of " + strings.Join(Buckets, ", ")}
	}
	q := url.Values{}
	q.Set("from", p.From.UTC().Format(time.RFC3339))
	q.Set("to", p.To.UTC().Format(time.RFC3339))
	q.Set("bucket", p.Bucket)
	if p.Tz != "" {
		q.Set("tz", p.Tz)
	}
	var out DerivedSeriesResponse
	err := d.client.do(ctx, "GET", "/derived/"+url.PathEscape(d.Key)+"/series", nil, "", q, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func seriesQuery(p SeriesParams) (url.Values, error) {
	if !IsValidBucket(p.Bucket) {
		return nil, &ValidationError{"invalid bucket " + strconv.Quote(p.Bucket) + "; expected one of " + strings.Join(Buckets, ", ")}
	}
	q := url.Values{}
	q.Set("from", p.From.UTC().Format(time.RFC3339))
	q.Set("to", p.To.UTC().Format(time.RFC3339))
	q.Set("bucket", p.Bucket)
	if p.Mode != "" {
		q.Set("mode", p.Mode)
	}
	if p.Tz != "" {
		q.Set("tz", p.Tz)
	}
	if p.Gapfill {
		q.Set("gapfill", "true")
	}
	return q, nil
}

func leaderboardQuery(p LeaderboardParams) url.Values {
	q := url.Values{}
	// Zero keeps Go's zero-value-means-unset idiom; anything else (including a negative)
	// is sent verbatim so the server's validation error surfaces instead of being masked.
	if p.Limit != 0 {
		q.Set("limit", strconv.Itoa(p.Limit))
	}
	if p.Offset != 0 {
		q.Set("offset", strconv.Itoa(p.Offset))
	}
	if p.Order != "" {
		q.Set("order", p.Order)
	}
	if p.Epoch != nil {
		q.Set("epoch", strconv.FormatInt(*p.Epoch, 10))
	}
	return q
}

func singleMemberWriteOpts(opts []MemberWriteOpts) (MemberWriteOpts, error) {
	if len(opts) == 0 {
		return MemberWriteOpts{}, nil
	}
	if len(opts) > 1 {
		return MemberWriteOpts{}, &ValidationError{"at most one MemberWriteOpts value may be supplied"}
	}
	return opts[0], nil
}

// List returns a page of counters.
func (c *Client) List(ctx context.Context, cursor string, limit int) (*CounterPage, error) {
	q := url.Values{}
	if cursor != "" {
		q.Set("cursor", cursor)
	}
	if limit > 0 {
		q.Set("limit", fmt.Sprintf("%d", limit))
	}
	var out CounterPage
	err := c.do(ctx, "GET", "/counters", nil, "", q, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) Usage(ctx context.Context) (*Usage, error) {
	var out Usage
	err := c.do(ctx, "GET", "/usage", nil, "", nil, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

// Flush sends any buffered operations now.
func (c *Client) Flush() error { return c.batcher.Flush() }

// Close flushes and stops the background timer.
func (c *Client) Close() error { return c.batcher.Close() }

func (c *Client) enqueue(key string, delta *big.Int) error {
	if c.batchEnabled {
		return c.batcher.enqueue(key, delta)
	}
	if c.batcher.isClosed() {
		return ErrClientClosed
	}
	op := Operation{CounterKey: key, IdempotencyKey: NewIdempotencyKey()}
	if delta.Sign() >= 0 {
		op.Op, op.Amount = "add", delta.String()
	} else {
		op.Op, op.Amount = "subtract", new(big.Int).Neg(delta).String()
	}
	// Fire-and-forget, like a background flush — so failures route to the same OnError sink
	// (previously they were dropped, which silently lost counted writes).
	go func() {
		if err := c.submitBatch(context.Background(), []Operation{op}); err != nil && c.onWriteError != nil {
			c.onWriteError(err)
		}
	}()
	return nil
}

type batchResult struct {
	CounterKey string `json:"counterKey"`
	Status     string `json:"status"`
	Error      *struct {
		Title  string `json:"title"`
		Status int    `json:"status"`
	} `json:"error"`
}

type batchResponse struct {
	Results []batchResult `json:"results"`
}

func (c *Client) submitBatch(ctx context.Context, ops []Operation) error {
	// A 200 only means the batch was accepted; each op carries its own status. Surface a per-op
	// "error" (e.g. a counter/quota cap) instead of silently dropping the buffered write.
	var resp batchResponse
	if err := c.do(ctx, "POST", "/batch", map[string]any{"operations": ops}, "", nil, &resp); err != nil {
		return err
	}
	failed := 0
	var first *batchResult
	for i := range resp.Results {
		if resp.Results[i].Status == "error" {
			failed++
			if first == nil {
				first = &resp.Results[i]
			}
		}
	}
	if first == nil {
		return nil
	}
	// a per-op problem carrying a status surfaces as an
	// *APIError with that status, exactly as if the operation had failed standalone. A per-op
	// problem with no status (or no problem object at all) has no failing HTTP status to carry —
	// never fabricate one (no Status 0): the problem the SDK cannot faithfully represent is
	// rejected client-side as a *ValidationError.
	title := "error"
	if first.Error != nil {
		title = first.Error.Title
	}
	msg := fmt.Sprintf("batch: %d operation(s) failed (%s: %s)", failed, first.CounterKey, title)
	if first.Error != nil && first.Error.Status != 0 {
		return &APIError{Status: first.Error.Status, Title: msg}
	}
	return &ValidationError{Msg: msg + "; per-op problem carries no status"}
}

var retryableStatus = map[int]bool{429: true, 500: true, 502: true, 503: true, 504: true}

// parseRetryAfter reads a Retry-After header as a non-negative integer number of seconds. Other forms
// (HTTP-date, garbage) return 0 so the caller falls back to exponential backoff.
func parseRetryAfter(v string) time.Duration {
	v = strings.TrimSpace(v)
	if secs, err := strconv.Atoi(v); err == nil && secs >= 0 {
		return time.Duration(secs) * time.Second
	}
	return 0
}

func (c *Client) do(ctx context.Context, method, path string, body any, idempotencyKey string, query url.Values, out any) error {
	u := c.baseURL + path
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	var reqBody []byte
	if body != nil {
		var err error
		if reqBody, err = json.Marshal(body); err != nil {
			return err
		}
	}

	sleepFn := c.sleepFn
	if sleepFn == nil {
		sleepFn = time.Sleep
	}
	var lastErr error
	var retryAfter time.Duration // 0 => use exponential backoff
	for attempt := 0; attempt <= c.maxRetries; attempt++ {
		if attempt > 0 {
			d := retryAfter
			if d <= 0 {
				d = c.backoff * time.Duration(1<<(attempt-1))
			}
			sleepFn(d)
		}
		retryAfter = 0
		req, err := http.NewRequestWithContext(ctx, method, u, bytes.NewReader(reqBody))
		if err != nil {
			return err
		}
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if idempotencyKey != "" {
			req.Header.Set("Idempotency-Key", idempotencyKey)
		}

		resp, err := c.httpClient.Do(req)
		if err != nil {
			lastErr = err // network — retry
			continue
		}
		status := resp.StatusCode
		if status >= 200 && status < 300 {
			if out != nil && status != 204 {
				err := json.NewDecoder(resp.Body).Decode(out)
				resp.Body.Close()
				if err != nil {
					// A non-JSON 2xx must not leak a raw *json.SyntaxError past the typed-error contract.
					return &APIError{Status: status, Title: fmt.Sprintf("malformed response body: %v", err)}
				}
				return nil
			}
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			return nil
		}
		if retryableStatus[status] && attempt < c.maxRetries {
			retryAfter = parseRetryAfter(resp.Header.Get("Retry-After"))
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			lastErr = &APIError{Status: status}
			continue
		}
		var p struct {
			Title string `json:"title"`
		}
		json.NewDecoder(resp.Body).Decode(&p)
		resp.Body.Close()
		return &APIError{Status: status, Title: p.Title}
	}
	// B2: retries exhausted with no HTTP response -> transport error (never a status-0 APIError).
	return &TransportError{Cause: fmt.Errorf("request failed after %d attempts: %w", c.maxRetries+1, lastErr)}
}

func orString(v, d string) string {
	if v == "" {
		return d
	}
	return v
}
func orInt(v, d int) int {
	if v == 0 {
		return d
	}
	return v
}
func orDur(v, d time.Duration) time.Duration {
	if v == 0 {
		return d
	}
	return v
}
func orHTTP(v *http.Client) *http.Client {
	if v == nil {
		return &http.Client{Timeout: 30 * time.Second}
	}
	return v
}
