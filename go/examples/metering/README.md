# Per-customer API metering

This sidecar sits in front of a small SaaS API and increments `usage:{customerID}` for every request. The `X-Customer-ID` header stands in for identity supplied by authentication middleware; production code should not trust a caller-provided billing identity. Set `COUNTERS_API_KEY` and `BILLING_CUSTOMER_ID`, then run `go run .` and send requests to `http://localhost:8080`.

The request path uses buffered `Add` calls because a high-volume API cannot wait for a counter-service round trip on every request. The important companion is `BatchOptions.OnError`: server confirmation happens later, so a quota rejection would otherwise become a silently lost billable write. Its callback receives `counters.Error`, and `errors.As` separates API rejections from transport failures, where no response was obtained at all.

A goroutine reads the configured customer's rolling 12-month series in `1d` buckets once at startup and then daily. Those buckets isolate the billing period, unlike the counter's lifetime value, and leave an auditable daily breakdown. Each `SeriesPoint` exposes its bucket start as `Timestamp time.Time` and its decimal-string delta as `Value`; the rollup parses `Value` directly into `math/big.Int`. Monthly volume accumulated over a year can exceed `int64`, and the string wire format preserves that value without a float round-trip.
