// counters.dev Go SDK — example app + end-to-end suite (mirrors the TS pilot at
// typescript/examples/e2e/main.mjs).
//
// This program is both living documentation and the E2E gate: it drives EVERY public method of
// the SDK against a real running server, asserts the outcomes, then replays the shared
// conformance/http vectors through the client. If a public method is not demonstrated here, the
// run fails — "if it isn't demonstrated, it isn't shipped."
//
// Env (see .github/actions/e2e-server): COUNTERS_BASE_URL (origin, no /v1), COUNTERS_API_KEY_A,
// COUNTERS_API_KEY_B, COUNTERS_PK_TOKEN (read-only token scoped to the fixed key "pk-demo").
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strconv"
	"strings"
	"time"

	counters "github.com/counters-dot-dev/counters.dev-sdk/go"
)

var (
	ctx     = context.Background()
	baseURL string
	keyA    string
	keyB    string
	pkToken string
	ns      string // run-unique namespace: fresh counters, stable epochs
	t0      time.Time
	invoked = map[string]bool{}
	checks  int
)

func required(name string) string {
	v := os.Getenv(name)
	if v == "" {
		fmt.Fprintf(os.Stderr, "missing required env: %s\n", name)
		os.Exit(2)
	}
	return v
}

func fail(msg string) {
	fmt.Fprintf(os.Stderr, "\nFAIL — %s\n", msg)
	os.Exit(1)
}

// check aborts the run on an unexpected error — the analogue of an uncaught throw in the TS pilot.
func check(err error, what string) {
	if err != nil {
		fail(what + ": " + err.Error())
	}
}

func assert(cond bool, what string) {
	checks++
	if !cond {
		fail("assertion failed: " + what)
	}
}

func assertEq[T comparable](actual, expected T, what string) {
	assert(actual == expected, fmt.Sprintf("%s: expected %#v, got %#v", what, expected, actual))
}

// expectStatus asserts that err is a *counters.APIError carrying the given HTTP status.
func expectStatus(err error, status int, what string) {
	checks++
	if err == nil {
		fail(fmt.Sprintf("%s: expected APIError(%d), but the call succeeded", what, status))
	}
	var apiErr *counters.APIError
	if !errors.As(err, &apiErr) || apiErr.Status != status {
		fail(fmt.Sprintf("%s: expected APIError(%d), got %v", what, status, err))
	}
}

func mustCounter(c *counters.Client, key string) *counters.CounterHandle {
	h, err := c.Counter(key)
	check(err, "counter("+key+")")
	invoked["Client.Counter"] = true
	return h
}

func sumPoints(points []counters.SeriesPoint, what string) string {
	sum := new(big.Int)
	for _, p := range points {
		v, ok := new(big.Int).SetString(p.V, 10)
		if !ok {
			fail(fmt.Sprintf("%s: unparseable series point value %q", what, p.V))
		}
		sum.Add(sum, v)
	}
	return sum.String()
}

func stringValue(v *string) string {
	if v == nil {
		return ""
	}
	return *v
}

func memberWriteOpts(op vectorOp, minutes func(int) time.Time) counters.MemberWriteOpts {
	opts := counters.MemberWriteOpts{Metadata: op.Metadata}
	if op.OccurredAtMin != nil {
		opts.OccurredAt = minutes(*op.OccurredAtMin)
	}
	return opts
}

func leaderboardEntries(entries []counters.LeaderboardEntry) []vectorEntryValue {
	out := make([]vectorEntryValue, 0, len(entries))
	for _, e := range entries {
		out = append(out, vectorEntryValue{rank: e.Rank, member: e.Member, value: e.Value, metadata: stringValue(e.Metadata)})
	}
	return out
}

func windowEntries(entries []counters.WindowEntry) []vectorEntryValue {
	out := make([]vectorEntryValue, 0, len(entries))
	for _, e := range entries {
		out = append(out, vectorEntryValue{rank: e.Rank, member: e.Member, value: e.Value})
	}
	return out
}

// ── 1. The grand tour: every public method, the way an integrator would use it ──────────────────

func tour() {
	client, err := counters.NewClient(counters.Options{
		APIKey:     keyA,
		BaseURL:    baseURL,
		HTTPClient: &http.Client{Timeout: 15 * time.Second},
		Batch: &counters.BatchOptions{
			Interval: 200 * time.Millisecond,
			OnError:  func(e error) { fmt.Fprintln(os.Stderr, "batch flush failed:", e) },
		},
	})
	check(err, "NewClient")
	invoked["NewClient"] = true

	// A typed handle per counter. Keys are validated client-side.
	signups := mustCounter(client, ns+"signups")

	// Confirmed writes: apply immediately, return the new state.
	first, err := signups.AddNow(ctx, 5)
	invoked["CounterHandle.AddNow"] = true
	check(err, "addNow(5)")
	assertEq(first.Value, "5", "addNow(5) on a fresh counter")
	assertEq(first.Epoch, int64(0), "fresh counter epoch")
	// createdAt/updatedAt are OPTIONAL on the Counter schema; the server does not populate them on
	// counter writes (its CounterSnapshot has no timestamps), so they arrive nil — the SDK models
	// them as nullable *time.Time. The other SDKs' e2e apps likewise assert only value+epoch here.

	afterSub, err := signups.SubtractNow(ctx, "2")
	invoked["CounterHandle.SubtractNow"] = true
	check(err, "subtractNow(2)")
	assertEq(afterSub.Value, "3", "subtractNow(2)")

	// Fire-and-forget writes: buffered, coalesced per counter, flushed in the background.
	check(signups.Add(big.NewInt(4)), "buffered add(4)")
	invoked["CounterHandle.Add"] = true
	check(signups.Subtract(1), "buffered subtract(1)")
	invoked["CounterHandle.Subtract"] = true
	check(client.Flush(), "flush")
	invoked["Client.Flush"] = true

	current, err := signups.Value(ctx)
	invoked["CounterHandle.Value"] = true
	check(err, "value")
	assertEq(current.Value, "6", "value after confirmed + buffered writes (5-2+4-1)")

	// Event-time writes: occurredAt buckets the op into the past; totals are unaffected.
	_, err = signups.AddNowAt(ctx, 10, t0.Add(-2*time.Hour))
	invoked["CounterHandle.AddNowAt"] = true
	check(err, "addNowAt(10, t0-2h)")
	afterSpool, err := signups.Value(ctx)
	check(err, "value after event-time write")
	assertEq(afterSpool.Value, "16", "total after an event-time write")

	// Series at every granularity the plan allows (pro: down to 1m). Sum == total delta.
	from := t0.Add(-24 * time.Hour)
	to := t0.Add(24 * time.Hour)
	for _, bucket := range []string{"1m", "5m", "1h", "1d", "1w", "1mo"} {
		series, err := signups.Series(ctx, counters.SeriesParams{From: from, To: to, Bucket: bucket, Mode: "delta"})
		check(err, "series("+bucket+")")
		assertEq(sumPoints(series.Points, "series("+bucket+")"), "16",
			"series("+bucket+") sums to the total delta")
	}
	invoked["CounterHandle.Series"] = true

	// B6: a non-enum bucket is rejected client-side as a *ValidationError (no request is sent).
	if _, err := signups.Series(ctx, counters.SeriesParams{From: from, To: to, Bucket: "2m"}); err == nil {
		fail("series with an invalid bucket should be rejected client-side (B6)")
	} else {
		var ve *counters.ValidationError
		if !errors.As(err, &ve) {
			fail(fmt.Sprintf("bad-bucket error should be *ValidationError, got %T", err))
		}
	}

	// Arbitrary precision is the headline guarantee: 10^32 round-trips exactly.
	bigC := mustCounter(client, ns+"big")
	const huge = "100000000000000000000000000000000"
	_, err = bigC.AddNow(ctx, huge)
	check(err, "addNow(10^32)")
	_, err = bigC.AddNow(ctx, 1)
	check(err, "addNow(1) on big")
	v, err := bigC.Value(ctx)
	check(err, "value(big)")
	assertEq(v.Value, "100000000000000000000000000000001", "10^32 + 1 round trip")
	_, err = bigC.SubtractNow(ctx, huge)
	check(err, "subtractNow(10^32)")
	v, err = bigC.Value(ctx)
	check(err, "value(big) after subtract")
	assertEq(v.Value, "1", "subtracting 10^32 back down")

	// Event-time decrement (the Go SDK splits occurredAt into the *NowAt methods).
	down, err := bigC.SubtractNowAt(ctx, 1, t0.Add(-time.Hour))
	invoked["CounterHandle.SubtractNowAt"] = true
	check(err, "subtractNowAt(1, t0-1h)")
	assertEq(down.Value, "0", "event-time decrement lands in the total")

	// List: pages in key order; follow NextCursor.
	for _, suffix := range []string{"pg-a", "pg-b", "pg-c"} {
		_, err := mustCounter(client, ns+suffix).AddNow(ctx, 1)
		check(err, "seed "+suffix)
	}
	var seen []string
	cursor := ""
	for {
		page, err := client.List(ctx, cursor, 2)
		check(err, "list")
		for _, c := range page.Data {
			seen = append(seen, c.Key)
		}
		cursor = page.NextCursor
		if cursor == "" {
			break
		}
	}
	invoked["Client.List"] = true
	wantOrder := []string{ns + "pg-a", ns + "pg-b", ns + "pg-c"}
	matched := 0
	for _, k := range seen {
		if matched < len(wantOrder) && k == wantOrder[matched] {
			matched++
		}
	}
	assertEq(matched, len(wantOrder), "list pagination walks all counters in key order")

	// Clear: value back to 0 in a new epoch; history is retained.
	cleared, err := signups.Clear(ctx)
	invoked["CounterHandle.Clear"] = true
	check(err, "clear")
	assertEq(cleared.Value, "0", "clear resets to zero")
	assertEq(cleared.Epoch, int64(1), "clear bumps the epoch")

	// Delete: tombstoned; further use is a 404.
	doomed := mustCounter(client, ns+"doomed")
	_, err = doomed.AddNow(ctx, 1)
	check(err, "addNow before delete")
	check(doomed.Delete(ctx), "delete")
	invoked["CounterHandle.Delete"] = true
	_, err = doomed.Value(ctx)
	expectStatus(err, 404, "value after delete")
	_, err = doomed.AddNow(ctx, 1)
	expectStatus(err, 404, "write after delete")

	// Tenant isolation: org B's key cannot see org A's counters.
	clientB, err := counters.NewClient(counters.Options{APIKey: keyB, BaseURL: baseURL})
	check(err, "NewClient(B)")
	_, err = mustCounter(clientB, ns+"signups").Value(ctx)
	expectStatus(err, 404, "cross-tenant read")
	check(clientB.Close(), "close(B)")

	// Publishable tokens: read-only, scoped. The pk_ token is just the bearer key.
	pkDemo := mustCounter(client, "pk-demo") // fixed key the token is scoped to
	_, err = pkDemo.AddNow(ctx, 1)           // ensure it exists before clearing (first run on a fresh DB)
	check(err, "pk-demo seed")
	_, err = pkDemo.Clear(ctx)
	check(err, "pk-demo clear")
	_, err = pkDemo.AddNow(ctx, 7)
	check(err, "pk-demo addNow(7)")
	pkClient, err := counters.NewClient(counters.Options{APIKey: pkToken, BaseURL: baseURL})
	check(err, "NewClient(pk)")
	pkVal, err := mustCounter(pkClient, "pk-demo").Value(ctx)
	check(err, "pk value")
	assertEq(pkVal.Value, "7", "pk token reads its scoped counter")
	_, err = mustCounter(pkClient, "pk-demo").Series(ctx, counters.SeriesParams{From: from, To: to, Bucket: "1h"})
	check(err, "pk series") // read surface also includes series
	_, err = mustCounter(pkClient, "pk-demo").AddNow(ctx, 1)
	expectStatus(err, 403, "pk token cannot write")
	_, err = mustCounter(pkClient, ns+"signups").Value(ctx)
	expectStatus(err, 403, "pk token cannot leave its scope")
	_, err = pkClient.List(ctx, "", 0)
	expectStatus(err, 403, "pk token cannot list")
	check(pkClient.Close(), "close(pk)")

	// Usage: org-wide quota state. Tolerant lower-bound assertions — this org wrote many counters above.
	usage, err := client.Usage(ctx)
	invoked["Client.Usage"] = true
	check(err, "usage")
	assert(usage.Ops.Used >= 1, "usage reports at least the writes this run made")
	assert(usage.Counters.Used >= 1, "usage reports at least one live counter")
	assert(usage.Ops.ResetsAt != "", "usage carries a resetsAt instant")
	assert(usage.Month != "", "usage carries the UTC month")

	check(client.Close(), "close(A)")
	invoked["Client.Close"] = true
}

// ── 1b. Leaderboards & members: the full board lifecycle against a live server ───────────────────

func leaderboards() {
	client, err := counters.NewClient(counters.Options{APIKey: keyA, BaseURL: baseURL})
	check(err, "NewClient(leaderboards)")

	// Sum board: three members accumulate deltas; the board tracks ranks + a group total.
	board := mustCounter(client, ns+"lb")
	alice, err := board.Member("alice")
	invoked["CounterHandle.Member"] = true
	check(err, "member(alice)")
	bob, err := board.Member("bob")
	check(err, "member(bob)")
	carol, err := board.Member("carol")
	check(err, "member(carol)")

	a1, err := alice.Add(ctx, 10)
	invoked["MemberHandle.Add"] = true
	check(err, "alice add")
	assertEq(a1.MemberValue, "10", "alice member add")
	assertEq(a1.MemberAccepted, true, "sum add is accepted")
	assertEq(a1.Mode, "sum", "first member add configures sum mode")
	assertEq(stringValue(a1.Value), "10", "board total after alice")
	_, err = bob.Add(ctx, 25)
	check(err, "bob add")
	c1, err := carol.Add(ctx, 10)
	check(err, "carol add")
	assertEq(stringValue(c1.Value), "45", "board total after three members")

	lb, err := board.Leaderboard(ctx, counters.LeaderboardParams{})
	invoked["CounterHandle.Leaderboard"] = true
	check(err, "leaderboard")
	assertEq(lb.Mode, "sum", "leaderboard mode")
	assertEq(lb.Order, "desc", "sum board order")
	assertEq(stringValue(lb.Total), "45", "leaderboard total")
	assertEq(lb.MemberCount, 3, "leaderboard member count")
	assertEq(lb.Entries[0].Member, "bob", "rank 1 member")
	assertEq(lb.Entries[0].Value, "25", "rank 1 value")
	assert(!lb.Entries[0].UpdatedAt.IsZero(), "leaderboard entry updatedAt decodes as time.Time")
	assertEq(lb.Entries[1].Rank, 2, "tie rank")
	assertEq(lb.Entries[2].Rank, 2, "tie rank shared")

	aSub, err := alice.Subtract(ctx, 5)
	invoked["MemberHandle.Subtract"] = true
	check(err, "alice subtract")
	assertEq(aSub.MemberValue, "5", "alice after subtract")
	assertEq(stringValue(aSub.Value), "40", "board total after subtract")

	snap, err := bob.Get(ctx, counters.MemberGetParams{})
	invoked["MemberHandle.Get"] = true
	check(err, "bob snapshot")
	assertEq(snap.Rank, 1, "bob snapshot rank")
	assertEq(snap.Value, "25", "bob snapshot value")
	assertEq(snap.Percentile, "100.00", "leader percentile")
	assert(!snap.UpdatedAt.IsZero(), "member snapshot updatedAt decodes as time.Time")

	removed, err := carol.Remove(ctx)
	invoked["MemberHandle.Remove"] = true
	check(err, "remove carol")
	assertEq(stringValue(removed.Value), "30", "board total after removing carol")
	lb2, err := board.Leaderboard(ctx, counters.LeaderboardParams{})
	check(err, "leaderboard after remove")
	assertEq(lb2.MemberCount, 2, "member count after removal")

	from := t0.Add(-24 * time.Hour)
	to := t0.Add(24 * time.Hour)
	_, err = board.MemberSeries(ctx, "alice", counters.SeriesParams{From: from, To: to, Bucket: "1h", Mode: "delta"})
	invoked["CounterHandle.MemberSeries"] = true
	expectStatus(err, 400, "member series without member series enabled")
	_, err = board.GroupSeries(ctx, counters.SeriesParams{From: from, To: to, Bucket: "1h", Mode: "delta"})
	invoked["CounterHandle.GroupSeries"] = true
	expectStatus(err, 400, "group series without member series enabled")
	_, err = board.WindowLeaderboard(ctx, counters.WindowLeaderboardParams{Window: "7d"})
	invoked["CounterHandle.WindowLeaderboard"] = true
	expectStatus(err, 400, "windowed leaderboard without member series enabled")

	// Score board (min): keep-best submits; a worse submit is successful but not accepted.
	raid := mustCounter(client, ns+"raid")
	team, err := raid.Member("alice|bob|carol")
	check(err, "team member")
	s1, err := team.Submit(ctx, 1502, counters.SubmitOpts{Mode: "min", Metadata: "room1:500"})
	invoked["MemberHandle.Submit"] = true
	check(err, "first min submit")
	assertEq(s1.MemberValue, "1502", "first min submit stands")
	assertEq(s1.MemberAccepted, true, "first submit accepted")
	assertEq(s1.Mode, "min", "submit configures min mode")
	s2, err := team.Submit(ctx, 1417, counters.SubmitOpts{Mode: "min", Metadata: "room1:400"})
	check(err, "better min submit")
	assertEq(s2.MemberValue, "1417", "better min kept")
	assertEq(s2.MemberAccepted, true, "better submit accepted")
	s3, err := team.Submit(ctx, 1600, counters.SubmitOpts{Mode: "min"})
	check(err, "worse min submit")
	assertEq(s3.MemberValue, "1417", "worse submit keeps standing best")
	assertEq(s3.MemberAccepted, false, "worse submit not accepted")

	teamSnap, err := team.Get(ctx, counters.MemberGetParams{})
	check(err, "team snapshot")
	assertEq(teamSnap.Value, "1417", "kept-best snapshot value")
	assertEq(stringValue(teamSnap.Metadata), "room1:400", "accepted metadata rides snapshot")

	dan, err := raid.Member("dan")
	check(err, "dan member")
	_, err = dan.Submit(ctx, 1300, counters.SubmitOpts{Mode: "min"})
	check(err, "dan submit")
	raidLb, err := raid.Leaderboard(ctx, counters.LeaderboardParams{})
	check(err, "raid leaderboard")
	assertEq(raidLb.Mode, "min", "raid mode")
	assertEq(raidLb.Order, "asc", "min board order")
	assertEq(raidLb.Entries[0].Member, "dan", "best min member")
	assertEq(raidLb.Entries[0].Value, "1300", "best min value")
	assertEq(raidLb.Entries[1].Member, "alice|bob|carol", "team rank 2")
	assertEq(stringValue(raidLb.Entries[1].Metadata), "room1:400", "entry metadata")

	check(client.Close(), "close leaderboards")
}

// ── 1c. Derived counters: read wiring + error mapping (definitions are dashboard-only) ───────────

func derived() {
	client, err := counters.NewClient(counters.Options{APIKey: keyA, BaseURL: baseURL})
	check(err, "NewClient(derived)")
	d, err := client.Derived(ns + "conversion")
	invoked["Client.Derived"] = true
	check(err, "derived handle")
	_, err = d.Value(ctx)
	invoked["DerivedHandle.Value"] = true
	expectStatus(err, 404, "derived value with no definition")
	_, err = d.Series(ctx, counters.DerivedSeriesParams{From: t0.Add(-time.Hour), To: t0, Bucket: "1h"})
	invoked["DerivedHandle.Series"] = true
	expectStatus(err, 404, "derived series with no definition")
	check(client.Close(), "close derived")
}

// ── 2. Shared conformance vectors, replayed through the real client ─────────────────────────────

type vectorFile struct {
	Cases []vectorCase `json:"cases"`
}

type vectorCase struct {
	Name  string       `json:"name"`
	Scope string       `json:"scope"`
	Steps []vectorStep `json:"steps"`
}

type vectorStep struct {
	Do     vectorOp     `json:"do"`
	Expect vectorExpect `json:"expect"`
}

type vectorOp struct {
	Org           string        `json:"org"`
	Op            string        `json:"op"`
	Key           string        `json:"key"`
	Amount        string        `json:"amount"`
	Member        string        `json:"member"`
	Value         string        `json:"value"`
	Mode          string        `json:"mode"`
	Metadata      string        `json:"metadata"`
	OccurredAtMin *int          `json:"occurredAtMin"`
	Series        *vectorSeries `json:"series"`
	List          *vectorList   `json:"list"`
	Limit         int           `json:"limit"`
	Offset        int           `json:"offset"`
	Order         string        `json:"order"`
	Epoch         *int64        `json:"epoch"`
	Window        string        `json:"window"`
}

type vectorSeries struct {
	FromMin int    `json:"fromMin"`
	ToMin   int    `json:"toMin"`
	Bucket  string `json:"bucket"`
	Gapfill bool   `json:"gapfill"`
	Tz      string `json:"tz"`
}

type vectorList struct {
	Limit int `json:"limit"`
}

type vectorExpect struct {
	Status          int      `json:"status"`
	Key             *string  `json:"key"`
	Value           *string  `json:"value"`
	Epoch           *int64   `json:"epoch"`
	PointsSum       *string  `json:"pointsSum"`
	PointsAtLeast   *int     `json:"pointsAtLeast"`
	ContainsInOrder []string `json:"containsInOrder"`
	Usage           *struct {
		OpsUsedAtLeast      *int  `json:"opsUsedAtLeast"`
		CountersUsedAtLeast *int  `json:"countersUsedAtLeast"`
		HasResetsAt         *bool `json:"hasResetsAt"`
	} `json:"usage"`
	MemberValue    *string       `json:"memberValue"`
	MemberAccepted *bool         `json:"memberAccepted"`
	Mode           *string       `json:"mode"`
	Order          *string       `json:"order"`
	Total          *string       `json:"total"`
	MemberCount    *int          `json:"memberCount"`
	Rank           *int          `json:"rank"`
	Percentile     *string       `json:"percentile"`
	Metadata       *string       `json:"metadata"`
	Entries        []vectorEntry `json:"entries"`
}

type vectorEntry struct {
	Rank     *int    `json:"rank"`
	Member   *string `json:"member"`
	Value    *string `json:"value"`
	Metadata *string `json:"metadata"`
}

// stepResult carries whatever the executed operation produced that expectations may reference.
type stepResult struct {
	key            string
	value          string
	epoch          int64
	points         []counters.SeriesPoint
	walked         []string
	usage          *counters.Usage
	memberValue    string
	memberAccepted bool
	mode           string
	order          string
	total          string
	memberCount    int
	rank           int
	percentile     string
	metadata       string
	entries        []vectorEntryValue
}

type vectorEntryValue struct {
	rank     int
	member   string
	value    string
	metadata string
}

func loadCases() []vectorCase {
	// examples/e2e -> examples -> go -> repo root (same walk the SDK's unit tests use).
	_, self, _, ok := runtime.Caller(0)
	if !ok {
		fail("runtime.Caller failed; cannot locate conformance/http/cases.json")
	}
	path := filepath.Join(filepath.Dir(self), "..", "..", "..", "conformance", "http", "cases.json")
	data, err := os.ReadFile(path)
	check(err, "read "+path)
	var f vectorFile
	check(json.Unmarshal(data, &f), "parse cases.json")
	return f.Cases
}

// runStep maps one vector op onto the SDK's public surface.
func runStep(client *counters.Client, handle *counters.CounterHandle, op vectorOp, minutes func(int) time.Time) (stepResult, error) {
	switch op.Op {
	case "add", "subtract":
		var c *counters.Counter
		var err error
		switch {
		case op.Op == "add" && op.OccurredAtMin != nil:
			c, err = handle.AddNowAt(ctx, op.Amount, minutes(*op.OccurredAtMin))
		case op.Op == "add":
			c, err = handle.AddNow(ctx, op.Amount)
		case op.OccurredAtMin != nil:
			c, err = handle.SubtractNowAt(ctx, op.Amount, minutes(*op.OccurredAtMin))
		default:
			c, err = handle.SubtractNow(ctx, op.Amount)
		}
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: c.Key, value: c.Value, epoch: c.Epoch}, nil
	case "clear":
		c, err := handle.Clear(ctx)
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: c.Key, value: c.Value, epoch: c.Epoch}, nil
	case "delete":
		return stepResult{}, handle.Delete(ctx)
	case "value":
		v, err := handle.Value(ctx)
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: v.Key, value: v.Value, epoch: v.Epoch}, nil
	case "series":
		p := op.Series
		s, err := handle.Series(ctx, counters.SeriesParams{
			From:    minutes(p.FromMin),
			To:      minutes(p.ToMin),
			Bucket:  p.Bucket,
			Tz:      p.Tz,
			Gapfill: p.Gapfill,
		})
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{points: s.Points}, nil
	case "list":
		limit := 50
		if op.List != nil && op.List.Limit > 0 {
			limit = op.List.Limit
		}
		var walked []string
		cursor := ""
		for {
			page, err := client.List(ctx, cursor, limit)
			if err != nil {
				return stepResult{}, err
			}
			for _, c := range page.Data {
				walked = append(walked, c.Key)
			}
			cursor = page.NextCursor
			if cursor == "" {
				break
			}
		}
		return stepResult{walked: walked}, nil
	case "usage":
		u, err := client.Usage(ctx)
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{usage: u}, nil
	case "memberAdd":
		m, err := handle.Member(op.Member)
		if err != nil {
			return stepResult{}, err
		}
		r, err := m.Add(ctx, op.Amount, memberWriteOpts(op, minutes))
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: r.Key, value: stringValue(r.Value), epoch: r.Epoch, memberValue: r.MemberValue, memberAccepted: r.MemberAccepted, mode: r.Mode}, nil
	case "memberSubtract":
		m, err := handle.Member(op.Member)
		if err != nil {
			return stepResult{}, err
		}
		r, err := m.Subtract(ctx, op.Amount, memberWriteOpts(op, minutes))
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: r.Key, value: stringValue(r.Value), epoch: r.Epoch, memberValue: r.MemberValue, memberAccepted: r.MemberAccepted, mode: r.Mode}, nil
	case "memberSubmit":
		m, err := handle.Member(op.Member)
		if err != nil {
			return stepResult{}, err
		}
		opts := memberWriteOpts(op, minutes)
		r, err := m.Submit(ctx, op.Value, counters.SubmitOpts{Mode: op.Mode, Metadata: opts.Metadata, OccurredAt: opts.OccurredAt})
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: r.Key, value: stringValue(r.Value), epoch: r.Epoch, memberValue: r.MemberValue, memberAccepted: r.MemberAccepted, mode: r.Mode}, nil
	case "memberGet":
		m, err := handle.Member(op.Member)
		if err != nil {
			return stepResult{}, err
		}
		r, err := m.Get(ctx, counters.MemberGetParams{Epoch: op.Epoch, Order: op.Order})
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: r.Key, value: r.Value, epoch: r.Epoch, rank: r.Rank, percentile: r.Percentile, memberCount: r.MemberCount, mode: r.Mode, metadata: stringValue(r.Metadata)}, nil
	case "memberRemove":
		m, err := handle.Member(op.Member)
		if err != nil {
			return stepResult{}, err
		}
		r, err := m.Remove(ctx)
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: r.Key, value: stringValue(r.Value), epoch: r.Epoch}, nil
	case "leaderboard":
		if op.Window != "" {
			r, err := handle.WindowLeaderboard(ctx, counters.WindowLeaderboardParams{
				Limit:  op.Limit,
				Offset: op.Offset,
				Order:  op.Order,
				Epoch:  op.Epoch,
				Window: op.Window,
			})
			if err != nil {
				return stepResult{}, err
			}
			return stepResult{key: r.Key, value: r.Total, mode: r.Mode, order: r.Order, total: r.Total, memberCount: r.MemberCount, entries: windowEntries(r.Entries)}, nil
		}
		r, err := handle.Leaderboard(ctx, counters.LeaderboardParams{Limit: op.Limit, Offset: op.Offset, Order: op.Order, Epoch: op.Epoch})
		if err != nil {
			return stepResult{}, err
		}
		return stepResult{key: r.Key, epoch: r.Epoch, mode: r.Mode, order: r.Order, total: stringValue(r.Total), memberCount: r.MemberCount, entries: leaderboardEntries(r.Entries)}, nil
	default:
		return stepResult{}, fmt.Errorf("vector op %q is not part of the SDK surface (case should be scope: http)", op.Op)
	}
}

func replayVectors() {
	clients := map[string]*counters.Client{}
	for label, key := range map[string]string{"A": keyA, "B": keyB} {
		c, err := counters.NewClient(counters.Options{APIKey: key, BaseURL: baseURL})
		check(err, "NewClient("+label+")")
		clients[label] = c
	}
	minutes := func(n int) time.Time { return t0.Add(time.Duration(n) * time.Minute) }

	var cases []vectorCase
	for _, c := range loadCases() {
		if c.Scope == "all" {
			cases = append(cases, c)
		}
	}
	assert(len(cases) >= 10, fmt.Sprintf("expected a healthy scope:all vector count, got %d", len(cases)))

	for i, c := range cases {
		prefix := fmt.Sprintf("%sc%d-", ns, i)
		for s, step := range c.Steps {
			op := step.Do
			expect := step.Expect
			where := fmt.Sprintf("%s step %d", c.Name, s)
			client := clients[op.Org]
			if client == nil {
				fail(where + ": unknown org label " + op.Org)
			}
			var handle *counters.CounterHandle
			if op.Key != "" {
				handle = mustCounter(client, prefix+op.Key)
			}

			res, err := runStep(client, handle, op, minutes)

			if expect.Status < 200 || expect.Status >= 300 {
				expectStatus(err, expect.Status, where)
				continue
			}
			if err != nil {
				fail(fmt.Sprintf("%s: expected success, got %v", where, err))
			}
			if expect.Key != nil {
				assertEq(res.key, prefix+*expect.Key, where+": key")
			}
			if expect.Value != nil {
				assertEq(res.value, *expect.Value, where+": value")
			}
			if expect.Epoch != nil {
				assertEq(res.epoch, *expect.Epoch, where+": epoch")
			}
			if expect.PointsSum != nil {
				assertEq(sumPoints(res.points, where), *expect.PointsSum, where+": pointsSum")
			}
			if expect.PointsAtLeast != nil {
				assert(len(res.points) >= *expect.PointsAtLeast,
					fmt.Sprintf("%s: pointsAtLeast %d, got %d", where, *expect.PointsAtLeast, len(res.points)))
			}
			if expect.ContainsInOrder != nil {
				m := 0
				for _, k := range res.walked {
					if m < len(expect.ContainsInOrder) && k == prefix+expect.ContainsInOrder[m] {
						m++
					}
				}
				assertEq(m, len(expect.ContainsInOrder), where+": containsInOrder")
			}
			if expect.Usage != nil {
				if expect.Usage.OpsUsedAtLeast != nil {
					assert(res.usage.Ops.Used >= int64(*expect.Usage.OpsUsedAtLeast),
						fmt.Sprintf("%s: opsUsedAtLeast %d, got %d", where, *expect.Usage.OpsUsedAtLeast, res.usage.Ops.Used))
				}
				if expect.Usage.CountersUsedAtLeast != nil {
					assert(res.usage.Counters.Used >= int64(*expect.Usage.CountersUsedAtLeast),
						fmt.Sprintf("%s: countersUsedAtLeast %d, got %d", where, *expect.Usage.CountersUsedAtLeast, res.usage.Counters.Used))
				}
				if expect.Usage.HasResetsAt != nil {
					assertEq(res.usage.Ops.ResetsAt != "", *expect.Usage.HasResetsAt, where+": hasResetsAt")
				}
			}
			if expect.MemberValue != nil {
				assertEq(res.memberValue, *expect.MemberValue, where+": memberValue")
			}
			if expect.MemberAccepted != nil {
				assertEq(res.memberAccepted, *expect.MemberAccepted, where+": memberAccepted")
			}
			if expect.Mode != nil {
				assertEq(res.mode, *expect.Mode, where+": mode")
			}
			if expect.Order != nil {
				assertEq(res.order, *expect.Order, where+": order")
			}
			if expect.Total != nil {
				assertEq(res.total, *expect.Total, where+": total")
			}
			if expect.MemberCount != nil {
				assertEq(res.memberCount, *expect.MemberCount, where+": memberCount")
			}
			if expect.Rank != nil {
				assertEq(res.rank, *expect.Rank, where+": rank")
			}
			if expect.Percentile != nil {
				assertEq(res.percentile, *expect.Percentile, where+": percentile")
			}
			if expect.Metadata != nil {
				assertEq(res.metadata, *expect.Metadata, where+": metadata")
			}
			if expect.Entries != nil {
				assertEq(len(res.entries), len(expect.Entries), where+": entries length")
				for i, want := range expect.Entries {
					got := res.entries[i]
					if want.Rank != nil {
						assertEq(got.rank, *want.Rank, fmt.Sprintf("%s: entry %d rank", where, i))
					}
					if want.Member != nil {
						assertEq(got.member, *want.Member, fmt.Sprintf("%s: entry %d member", where, i))
					}
					if want.Value != nil {
						assertEq(got.value, *want.Value, fmt.Sprintf("%s: entry %d value", where, i))
					}
					if want.Metadata != nil {
						assertEq(got.metadata, *want.Metadata, fmt.Sprintf("%s: entry %d metadata", where, i))
					}
				}
			}
		}
		fmt.Printf("  ok   vector: %s\n", c.Name)
	}
	for label, c := range clients {
		check(c.Close(), "close vector client "+label)
	}
}

// ── 3. Surface-completeness gate: no public method may go undemonstrated ────────────────────────

func surfaceGate() {
	for _, entry := range []struct {
		name string
		typ  reflect.Type
	}{
		{"Client", reflect.TypeOf((*counters.Client)(nil))},
		{"CounterHandle", reflect.TypeOf((*counters.CounterHandle)(nil))},
		{"MemberHandle", reflect.TypeOf((*counters.MemberHandle)(nil))},
		{"DerivedHandle", reflect.TypeOf((*counters.DerivedHandle)(nil))},
	} {
		// reflect enumerates exported methods only — exactly the public surface. Any new exported
		// method that this example app never invoked fails the gate.
		for i := 0; i < entry.typ.NumMethod(); i++ {
			tag := entry.name + "." + entry.typ.Method(i).Name
			assert(invoked[tag], fmt.Sprintf(
				"public method %s was never demonstrated by this example app — demonstrate it here (the invoked set is maintained at each call site)", tag))
		}
	}
	assert(invoked["NewClient"], "constructor demonstrated")
}

// ── main ─────────────────────────────────────────────────────────────────────────────────────────

func main() {
	origin := strings.TrimSuffix(required("COUNTERS_BASE_URL"), "/")
	keyA = required("COUNTERS_API_KEY_A")
	keyB = required("COUNTERS_API_KEY_B")
	pkToken = required("COUNTERS_PK_TOKEN")
	baseURL = origin + "/v1"
	ns = "e2e-go-" + strconv.FormatInt(time.Now().UnixNano(), 36) + "-"
	t0 = time.Now().UTC().Truncate(time.Second)

	fmt.Printf("counters.dev Go SDK e2e — %s (ns %s)\n", baseURL, ns)
	tour()
	fmt.Println("  ok   full public-surface tour")
	leaderboards()
	fmt.Println("  ok   leaderboards + members lifecycle")
	derived()
	fmt.Println("  ok   derived-counter read wiring")
	replayVectors()
	surfaceGate()
	fmt.Printf("\nPASS — entire public SDK surface + shared vectors verified against a live server (%d assertions)\n", checks)
}
