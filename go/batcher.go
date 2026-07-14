package counters

import (
	"math/big"
	"sync"
	"time"
)

type submitFn func(ops []operation) ([]WriteFailure, error)

type bufferedWrite struct {
	delta          *big.Int
	idempotencyKey string
}

// batcher coalesces add/subtract per counter into one net operation per flush.
type batcher struct {
	submit  submitFn
	maxSize int
	onError func(WriteFailure)

	mu     sync.Mutex
	buf    map[string]*bufferedWrite
	ticker *time.Ticker
	done   chan struct{}
	closed bool
}

func newBatcher(submit submitFn, maxSize int, interval time.Duration, onError func(WriteFailure)) *batcher {
	b := &batcher{submit: submit, maxSize: maxSize, onError: onError, buf: map[string]*bufferedWrite{}}
	if interval > 0 {
		ticker := time.NewTicker(interval)
		b.ticker = ticker
		b.done = make(chan struct{})
		// Capture ticker.C in the closure: Close() nils b.ticker under the lock, so the worker must
		// not read b.ticker.C unsynchronized on each loop.
		go func() {
			for {
				select {
				case <-ticker.C:
					b.flushSafe()
				case <-b.done:
					return
				}
			}
		}()
	}
	return b
}

func (b *batcher) enqueue(key string, delta *big.Int) error {
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		return ErrClientClosed
	}
	cur, ok := b.buf[key]
	if !ok {
		idempotencyKey, err := newIdempotencyKey()
		if err != nil {
			b.mu.Unlock()
			return err
		}
		cur = &bufferedWrite{delta: new(big.Int), idempotencyKey: idempotencyKey}
		b.buf[key] = cur
	}
	cur.delta.Add(cur.delta, delta)
	size := len(b.buf)
	b.mu.Unlock()
	if size >= b.maxSize {
		go b.flushSafe()
	}
	return nil
}

func (b *batcher) isClosed() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.closed
}

func (b *batcher) pending() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.buf)
}

// Flush drains the buffer into one batch and submits it.
func (b *batcher) Flush() error {
	_, err := b.flush()
	return err
}

func (b *batcher) flush() ([]WriteFailure, error) {
	b.mu.Lock()
	ops := make([]operation, 0, len(b.buf))
	for key, buffered := range b.buf {
		delta := buffered.delta
		if delta.Sign() == 0 {
			continue // add then equal subtract -> net no-op
		}
		if delta.Sign() > 0 {
			ops = append(ops, operation{CounterKey: key, Operation: "add", Amount: delta.String(), IdempotencyKey: buffered.idempotencyKey})
		} else {
			ops = append(ops, operation{CounterKey: key, Operation: "subtract", Amount: new(big.Int).Neg(delta).String(), IdempotencyKey: buffered.idempotencyKey})
		}
	}
	b.buf = map[string]*bufferedWrite{}
	b.mu.Unlock()
	if len(ops) == 0 {
		return nil, nil
	}
	return b.submit(ops)
}

func (b *batcher) flushSafe() {
	failures, err := b.flush()
	if err == nil || b.onError == nil {
		return
	}
	for _, failure := range failures {
		b.onError(failure)
	}
}

// Close stops the timer and flushes the remainder.
func (b *batcher) Close() error {
	b.mu.Lock()
	b.closed = true
	if b.ticker != nil {
		b.ticker.Stop()
		close(b.done)
		b.ticker = nil
	}
	b.mu.Unlock()
	return b.Flush()
}
