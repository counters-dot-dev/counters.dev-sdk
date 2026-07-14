import { CountersError } from "./errors.js";
import { newIdempotencyKey } from "./idempotency.js";
import type { Operation } from "./types.js";

export type SubmitFn = (ops: Operation[]) => Promise<void>;

export interface BatcherOptions {
  maxBatchSize: number;
  intervalMs: number;
  onError?: (err: unknown) => void;
}

/**
 * Client-side coalescing buffer. Multiple add/subtract on the same counter are summed into a single net
 * operation per flush — the series granularity is >= 5m, so sub-flush coalescing loses no meaningful detail,
 * and it collapses thousands of increments into one request.
 */
export class Batcher {
  private readonly buf = new Map<string, bigint>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private closed = false;

  constructor(
    private readonly submit: SubmitFn,
    private readonly opts: BatcherOptions,
  ) {}

  enqueue(counterKey: string, delta: bigint): void {
    // A write after close() would silently strand in the buffer (its worker is gone) or re-arm the
    // interval timer on an already-closed client — surface the misuse instead.
    if (this.closed) throw new CountersError("cannot enqueue on a closed client");
    this.buf.set(counterKey, (this.buf.get(counterKey) ?? 0n) + delta);
    if (this.opts.intervalMs > 0 && this.timer === null) this.startTimer();
    if (this.buf.size >= this.opts.maxBatchSize) this.flushSafe();
  }

  pending(): number {
    return this.buf.size;
  }

  /** Whether close() has been called (used by the client's immediate-mode path to reject late writes). */
  isClosed(): boolean {
    return this.closed;
  }

  /** Drain the current buffer into one batch and submit it. */
  async flush(): Promise<void> {
    const ops = this.drain();
    if (ops.length === 0) return;
    await this.submit(ops);
  }

  /** Stop the timer and flush everything (looping in case items arrived mid-flush). */
  async close(): Promise<void> {
    this.closed = true;
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
    while (this.buf.size > 0) await this.flush();
  }

  private drain(): Operation[] {
    const ops: Operation[] = [];
    for (const [counterKey, delta] of this.buf) {
      if (delta === 0n) continue; // add then equal subtract => net no-op
      ops.push(
        delta > 0n
          ? { counterKey, op: "add", amount: delta.toString(), idempotencyKey: newIdempotencyKey() }
          : { counterKey, op: "subtract", amount: (-delta).toString(), idempotencyKey: newIdempotencyKey() },
      );
    }
    this.buf.clear();
    return ops;
  }

  private startTimer(): void {
    this.timer = setInterval(() => this.flushSafe(), this.opts.intervalMs);
    (this.timer as { unref?: () => void }).unref?.();
  }

  private flushSafe(): void {
    this.flush().catch((err) => this.opts.onError?.(err));
  }
}
