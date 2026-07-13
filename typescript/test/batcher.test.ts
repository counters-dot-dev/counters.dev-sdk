import { describe, expect, it, vi } from "vitest";
import { Batcher } from "../src/batcher.js";
import { CountersError } from "../src/errors.js";
import type { Operation } from "../src/types.js";

function collector() {
  const batches: Operation[][] = [];
  const submit = vi.fn(async (ops: Operation[]) => {
    batches.push(ops);
  });
  return { batches, submit };
}

describe("Batcher coalescing", () => {
  it("sums multiple adds into one op", async () => {
    const { batches, submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 0 });
    b.enqueue("c", 1n);
    b.enqueue("c", 2n);
    b.enqueue("c", 3n);
    await b.flush();
    expect(batches).toHaveLength(1);
    expect(batches[0]).toHaveLength(1);
    expect(batches[0]![0]).toMatchObject({ counterKey: "c", op: "add", amount: "6" });
    expect(batches[0]![0]!.idempotencyKey).toBeTruthy();
  });

  it("nets adds and subtracts to a positive add", async () => {
    const { batches, submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 0 });
    b.enqueue("c", 10n);
    b.enqueue("c", -3n);
    await b.flush();
    expect(batches[0]![0]).toMatchObject({ op: "add", amount: "7" });
  });

  it("emits a subtract when the net is negative", async () => {
    const { batches, submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 0 });
    b.enqueue("c", 2n);
    b.enqueue("c", -9n);
    await b.flush();
    expect(batches[0]![0]).toMatchObject({ op: "subtract", amount: "7" });
  });

  it("skips net-zero counters entirely", async () => {
    const { batches, submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 0 });
    b.enqueue("c", 5n);
    b.enqueue("c", -5n);
    await b.flush();
    expect(submit).not.toHaveBeenCalled();
    expect(batches).toHaveLength(0);
  });

  it("keeps separate counters as separate ops", async () => {
    const { batches, submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 0 });
    b.enqueue("a", 1n);
    b.enqueue("b", 2n);
    await b.flush();
    expect(batches[0]).toHaveLength(2);
  });

  it("auto-flushes when maxBatchSize distinct counters are buffered", async () => {
    const { batches, submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 2, intervalMs: 0 });
    b.enqueue("a", 1n);
    b.enqueue("b", 1n); // size hits 2 -> background flush
    await vi.waitFor(() => expect(submit).toHaveBeenCalled());
    expect(batches[0]).toHaveLength(2);
    expect(b.pending()).toBe(0);
  });

  it("flushes on the interval timer", async () => {
    vi.useFakeTimers();
    try {
      const { submit } = collector();
      const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 1000 });
      b.enqueue("a", 1n);
      await vi.advanceTimersByTimeAsync(1000);
      expect(submit).toHaveBeenCalledOnce();
    } finally {
      vi.useRealTimers();
    }
  });

  it("close() flushes remaining work and stops the timer", async () => {
    const { batches, submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 1000 });
    b.enqueue("a", 1n);
    await b.close();
    expect(batches[0]).toHaveLength(1);
  });

  it("routes background-flush errors to onError", async () => {
    const onError = vi.fn();
    const submit = vi.fn(async () => {
      throw new Error("boom");
    });
    const b = new Batcher(submit, { maxBatchSize: 1, intervalMs: 0, onError });
    b.enqueue("a", 1n); // triggers a background flush that rejects
    await vi.waitFor(() => expect(onError).toHaveBeenCalled());
  });

  it("throws on enqueue after close (no timer resurrection, no stranded write)", async () => {
    const { submit } = collector();
    const b = new Batcher(submit, { maxBatchSize: 1000, intervalMs: 1000 });
    await b.close();
    expect(() => b.enqueue("a", 1n)).toThrow(CountersError);
    expect(b.pending()).toBe(0); // the rejected write did not land in the buffer
  });
});
