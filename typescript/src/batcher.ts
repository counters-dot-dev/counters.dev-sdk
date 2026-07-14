import { CountersError, CountersTransportError, CountersValidationError } from "./errors.js";
import { newIdempotencyKey } from "./idempotency.js";
import { describeValue } from "./validation.js";
import type { Operation, WriteFailure } from "./types.js";

export interface OperationFailure {
  readonly operation: Operation;
  readonly error: CountersError;
}

export type SubmitFn = (
  ops: Operation[],
) => Promise<void | readonly OperationFailure[]>;

export interface BatcherOptions {
  maxBatchSize: number;
  intervalMs: number;
  onError?: (failure: WriteFailure) => void;
}

/**
 * Client-side coalescing buffer. Multiple add/subtract on the same counter are summed into a single net
 * operation per flush — the series granularity is >= 5m, so sub-flush coalescing loses no meaningful detail,
 * and it collapses thousands of increments into one request.
 */
export class Batcher {
  private readonly buf = new Map<string, { delta: bigint; idempotencyKey: string }>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private closed = false;

  constructor(
    private readonly submit: SubmitFn,
    private readonly opts: BatcherOptions,
  ) {}

  enqueue(counterKey: string, delta: bigint): void {
    // A write after close() would silently strand in the buffer (its worker is gone) or re-arm the
    // interval timer on an already-closed client — surface the misuse instead.
    if (this.closed) throw new CountersValidationError("cannot enqueue on a closed client");
    const buffered = this.buf.get(counterKey);
    if (buffered === undefined) {
      // Generate before accepting the write into the buffer. If the platform RNG is unavailable,
      // the caller receives a typed transport failure synchronously instead of a detached timer
      // throwing later with no identity-bearing callback possible (no key was ever sent).
      this.buf.set(counterKey, { delta, idempotencyKey: newIdempotencyKey() });
    } else {
      buffered.delta += delta;
    }
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
    const failures = await this.submitFailures(ops);
    if (failures.length > 0) throw failures[0]!.error;
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
    for (const [counterKey, buffered] of this.buf) {
      const { delta, idempotencyKey } = buffered;
      if (delta === 0n) continue; // add then equal subtract => net no-op
      ops.push(
        delta > 0n
          ? { counterKey, operation: "add", amount: delta.toString(), idempotencyKey }
          : {
              counterKey,
              operation: "subtract",
              amount: (-delta).toString(),
              idempotencyKey,
            },
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
    const ops = this.drain();
    if (ops.length === 0) return;
    void this.submitFailures(ops).then((failures) => {
      for (const failure of failures) this.opts.onError?.(toWriteFailure(failure));
    });
  }

  private async submitFailures(ops: Operation[]): Promise<OperationFailure[]> {
    try {
      return [...((await this.submit(ops)) ?? [])];
    } catch (error) {
      const normalised = normaliseBatchError(error);
      return ops.map((operation) => ({ operation, error: normalised }));
    }
  }
}

function normaliseBatchError(error: unknown): CountersError {
  return error instanceof CountersError
    ? error
    : new CountersTransportError(
        `unexpected batch submission failure: ${describeValue(error)}`,
        error,
      );
}

/** Convert an internal wire operation + typed error into the frozen public callback payload. */
export function toWriteFailure(failure: OperationFailure): WriteFailure {
  const { operation, error } = failure;
  const amount = operation.amount ?? "0";
  const delta = operation.operation === "subtract" && amount !== "0" ? `-${amount}` : amount;
  return {
    counterKey: operation.counterKey,
    delta,
    idempotencyKey: operation.idempotencyKey ?? "",
    error,
  };
}
