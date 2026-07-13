# Counters Go SDK

Pure Go client for counters.dev. The SDK uses only the standard library.

## Install

```sh
go get github.com/counters-dot-dev/counters.dev-sdk/go
```

## Basic Usage

```go
client, err := counters.NewClient(counters.Options{APIKey: "ck_live_..."})
if err != nil {
	panic(err)
}
defer client.Close()

signups, err := client.Counter("signups")
if err != nil {
	panic(err)
}

_, _ = signups.AddNow(context.Background(), "100000000000000000000")
value, _ := signups.Value(context.Background())
fmt.Println(value.Value) // values are decimal strings
```

Buffered counter writes use `Add`/`Subtract` and are coalesced until `Flush` or `Close`. Immediate writes use `AddNow`, `SubtractNow`, and the `*NowAt` variants for event-time series bucketing.

## Usage

```go
usage, err := client.Usage(context.Background())
if err != nil {
	return err
}
fmt.Println(usage.Month, usage.Ops.Used, usage.Ops.ResetsAt)
```

Quota fields that may be unlimited are pointers: `usage.Ops.Quota` and `usage.Limits.MonthlyOpsQuota` are `nil` when the plan has no cap.

## Series

```go
series, err := signups.Series(context.Background(), counters.SeriesParams{
	From:   from,
	To:     to,
	Bucket: "1h",
	Mode:   "delta",
})
```

For dimensional member series, use the separate Go methods:

```go
one, err := signups.MemberSeries(ctx, "alice", counters.SeriesParams{From: from, To: to, Bucket: "1h"})
grouped, err := signups.GroupSeries(ctx, counters.SeriesParams{From: from, To: to, Bucket: "1h"})
```

## Members And Leaderboards

```go
board, _ := client.Counter("raid")
alice, _ := board.Member("alice")

result, err := alice.Add(ctx, 10, counters.MemberWriteOpts{
	Metadata: "room1:500",
})
fmt.Println(result.MemberValue, result.MemberAccepted, result.Mode)

score, err := alice.Submit(ctx, 1417, counters.SubmitOpts{
	Mode:     "min",
	Metadata: "room1:400",
})
fmt.Println(score.MemberValue)

snapshot, _ := alice.Get(ctx, counters.MemberGetParams{})
fmt.Println(snapshot.Rank, snapshot.Percentile)

leaderboard, _ := board.Leaderboard(ctx, counters.LeaderboardParams{Limit: 25, Order: "desc"})
fmt.Println(*leaderboard.Total)
```

`Leaderboard.Total`, `MemberValue.Value`, `MemberRemoved.Value`, entry metadata, and snapshot metadata are pointers because the API may omit them. Windowed reads are separate in Go:

```go
window, err := board.WindowLeaderboard(ctx, counters.WindowLeaderboardParams{Window: "7d"})
```

Every immediate write carries a fresh idempotency key.

## Derived Counters

```go
derived, _ := client.Derived("conversion")
current, err := derived.Value(ctx)
if err != nil {
	return err
}
if current.Value == nil {
	fmt.Println(*current.Reason)
} else {
	fmt.Println(*current.Value)
}
```

Derived values are decimal strings. JSON `null` values remain `nil` with a reason, and the SDK never parses decimals into floats.

## Validation

The SDK validates counter keys, member keys, amounts, signed member submit values, metadata size, series buckets, and leaderboard windows locally. Metadata is capped at 1024 UTF-8 bytes, not characters.

## Tests

```sh
GOCACHE=/tmp/go-build go test ./...
```

The shared conformance vectors live under `../conformance`.
