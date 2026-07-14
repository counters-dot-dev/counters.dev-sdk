package counters

import (
	"math/big"
	"testing"
	"time"
)

func TestBatcherCoalesce(t *testing.T) {
	var captured [][]Operation
	b := newBatcher(func(ops []Operation) ([]WriteFailure, error) {
		captured = append(captured, ops)
		return nil, nil
	}, 1000, 0, nil)
	b.enqueue("c", big.NewInt(1))
	b.enqueue("c", big.NewInt(2))
	b.enqueue("c", big.NewInt(3))
	if err := b.Flush(); err != nil {
		t.Fatal(err)
	}
	if len(captured) != 1 || len(captured[0]) != 1 {
		t.Fatalf("got %v", captured)
	}
	if captured[0][0].Amount != "6" || captured[0][0].Operation != "add" {
		t.Errorf("op=%+v", captured[0][0])
	}
}

func TestBatcherNetNegativeAndZeroSkip(t *testing.T) {
	var captured [][]Operation
	b := newBatcher(func(ops []Operation) ([]WriteFailure, error) {
		captured = append(captured, ops)
		return nil, nil
	}, 1000, 0, nil)
	b.enqueue("a", big.NewInt(2))
	b.enqueue("a", big.NewInt(-9)) // net -7 -> subtract 7
	b.enqueue("z", big.NewInt(5))
	b.enqueue("z", big.NewInt(-5)) // net 0 -> skipped
	if err := b.Flush(); err != nil {
		t.Fatal(err)
	}
	if len(captured) != 1 || len(captured[0]) != 1 {
		t.Fatalf("expected 1 op (z skipped), got %v", captured)
	}
	if captured[0][0].Operation != "subtract" || captured[0][0].Amount != "7" {
		t.Errorf("op=%+v", captured[0][0])
	}
}

func TestBatcherMaxSizeFlush(t *testing.T) {
	flushed := make(chan []Operation, 4)
	b := newBatcher(func(ops []Operation) ([]WriteFailure, error) {
		flushed <- ops
		return nil, nil
	}, 2, 0, nil)
	b.enqueue("a", big.NewInt(1))
	b.enqueue("b", big.NewInt(1)) // size hits 2 -> async flush
	select {
	case ops := <-flushed:
		if len(ops) != 2 {
			t.Errorf("ops=%d", len(ops))
		}
	case <-time.After(2 * time.Second):
		t.Fatal("flush did not happen at maxBatchSize")
	}
}
