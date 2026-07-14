package main

import (
	"context"
	"errors"
	"log"
	"math/big"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	counters "github.com/counters-dot-dev/counters.dev-sdk/go"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	apiKey, rollupCustomer := os.Getenv("COUNTERS_API_KEY"), os.Getenv("BILLING_CUSTOMER_ID")
	if apiKey == "" || rollupCustomer == "" {
		log.Fatal("COUNTERS_API_KEY and BILLING_CUSTOMER_ID are required")
	}

	client, err := counters.NewClient(counters.Options{
		APIKey: apiKey,
		Batch: &counters.BatchOptions{
			// Add returns after enqueueing: millions of requests cannot each pay for an API
			// round trip. Server confirmation happens on the background flush; without this
			// sink, a quota rejection would silently lose billable usage.
			OnError: func(failure counters.WriteFailure) {
				reportWriteFailure("asynchronous usage write", failure)
			},
		},
	})
	if err != nil {
		log.Fatal(err)
	}
	rollupUsage, err := client.Counter("usage:" + rollupCustomer)
	if err != nil {
		log.Fatal(err)
	}
	go dailyRollup(ctx, rollupUsage, rollupCustomer)

	server := &http.Server{Addr: ":8080", Handler: metered(client)}
	shutdownDone := make(chan struct{})
	context.AfterFunc(ctx, func() { _ = server.Shutdown(context.Background()); close(shutdownDone) })
	serveErr := server.ListenAndServe()
	stop()
	<-shutdownDone
	reportCounterError("final usage flush", client.Close())
	if !errors.Is(serveErr, http.ErrServerClosed) {
		log.Fatal(serveErr)
	}
}

func metered(client *counters.Client) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// In a real service this ID comes from authenticated request context, not a trusted client header.
		customerID := r.Header.Get("X-Customer-ID")
		usage, err := client.Counter("usage:" + customerID)
		if customerID == "" || err != nil {
			http.Error(w, "invalid customer ID", http.StatusBadRequest)
			return
		}
		// This confirms only that the increment entered the local batch; the write is confirmed
		// asynchronously, trading per-request certainty for throughput.
		if err := usage.Add(1); err != nil {
			http.Error(w, "metering unavailable", http.StatusServiceUnavailable)
			return
		}
		// A real sidecar would forward to the SaaS API here; returning is an empty 200 in this sketch.
	})
}

func reportWriteFailure(operation string, failure counters.WriteFailure) {
	member := failure.Member
	if member == "" {
		member = "-"
	}
	// These are the durable reconciliation coordinates: the exact coalesced delta and the
	// idempotency key actually sent, rather than only the fact that some customer's write failed.
	log.Printf("%s needs reconciliation: counter=%s delta=%s member=%s idempotency_key=%s",
		operation, failure.CounterKey, failure.Delta, member, failure.IdempotencyKey)
	reportCounterError(operation, failure.Err)
}

func reportCounterError(operation string, err error) {
	var apiErr *counters.APIError
	var transportErr *counters.TransportError
	var validationErr *counters.ValidationError
	switch {
	case errors.As(err, &apiErr):
		// An API error means the service replied; a 403 is a quota rejection that needs reconciliation.
		log.Printf("%s rejected by counter API (status=%d): %v", operation, apiErr.Status, apiErr)
	case errors.As(err, &transportErr):
		// No response was obtained: reachability is a different operational incident from rejection.
		log.Printf("%s failed because counter service was unreachable: %v", operation, transportErr)
	case errors.As(err, &validationErr):
		log.Printf("%s rejected by SDK validation: %v", operation, validationErr)
	}
}

func dailyRollup(ctx context.Context, usage *counters.CounterHandle, customerID string) {
	for {
		now := time.Now().UTC()
		// Unlike Value (the lifetime total), a 1d series isolates the billing period and leaves
		// auditable daily buckets behind the figure.
		series, err := usage.Series(ctx, counters.SeriesParams{
			From: now.AddDate(-1, 0, 0), To: now, Bucket: "1d", Mode: "delta", TimeZone: "UTC",
		})
		if err != nil {
			reportCounterError("daily billing rollup", err)
		} else {
			// This rolling year of monthly volume can exceed int64. Decimal strings let billing
			// land directly in big.Int, without a lossy float round-trip.
			total := new(big.Int)
			for _, point := range series.Points {
				value, ok := new(big.Int).SetString(point.Value, 10)
				if !ok {
					log.Printf("daily billing rollup returned invalid counter value %q", point.Value)
					return
				}
				total.Add(total, value)
			}
			log.Printf("rolling-year usage customer=%s requests=%s", customerID, total)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(24 * time.Hour):
		}
	}
}
