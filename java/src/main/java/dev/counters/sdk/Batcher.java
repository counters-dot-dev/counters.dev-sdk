package dev.counters.sdk;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Client-side coalescing buffer. Multiple add/subtract on the same counter are summed into a single net
 * operation per flush — the series granularity is >= 5m, so sub-flush coalescing loses no meaningful detail,
 * and it collapses thousands of increments into one request.
 *
 * <p>The background timer runs on a lazily started single-thread <em>daemon</em>
 * {@link ScheduledExecutorService}, so a buffering client never prevents JVM exit — but call
 * {@link #close()} before exit to avoid losing buffered writes.
 */
final class Batcher {

    @FunctionalInterface
    interface SubmitFn {
        List<WriteFailure> submit(List<Operation> operations);
    }

    private final SubmitFn submit;
    private final int maxBatchSize;
    private final long intervalMillis;
    private final Consumer<WriteFailure> onError;

    private final Object lock = new Object();
    private final Map<String, BufferedWrite> buf = new LinkedHashMap<>();
    private ScheduledExecutorService executor; // lazily created; daemon thread
    private boolean timerStarted;
    private boolean closed;

    Batcher(
            SubmitFn submit,
            int maxBatchSize,
            long intervalMillis,
            Consumer<WriteFailure> onError) {
        this.submit = submit;
        this.maxBatchSize = maxBatchSize;
        this.intervalMillis = intervalMillis;
        this.onError = onError;
    }

    /** Merge a signed delta into the buffer; starts the timer on first use and flushes at maxBatchSize. */
    void enqueue(String counterKey, BigInteger delta) {
        synchronized (lock) {
            // A write after close() would re-arm the timer on a fresh executor and strand in a buffer
            // no one drains — reject it instead.
            if (closed) throw new CountersValidationException("cannot enqueue on a closed client");
            BufferedWrite buffered = buf.get(counterKey);
            if (buffered == null) {
                // Generate before accepting the write. If the runtime cannot obtain randomness,
                // enqueue fails synchronously with the transport taxonomy instead of a timer task
                // failing later after the caller has lost the write's identity.
                buf.put(counterKey, new BufferedWrite(delta, Idempotency.newKey()));
            } else {
                buf.put(counterKey, new BufferedWrite(buffered.delta().add(delta), buffered.idempotencyKey()));
            }
            if (intervalMillis > 0 && !timerStarted) {
                timerStarted = true;
                executor().scheduleAtFixedRate(this::flushSafe, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            }
            if (buf.size() >= maxBatchSize) {
                executor().execute(this::flushSafe); // off the caller's thread, like the timer path
            }
        }
    }

    /** Whether close() has been called (used by the client's immediate-mode path to reject late writes). */
    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Number of distinct counters currently buffered. */
    int pending() {
        synchronized (lock) {
            return buf.size();
        }
    }

    /** Whether a periodic timer has been armed (package-private test seam). */
    boolean isTimerStarted() {
        synchronized (lock) {
            return timerStarted;
        }
    }

    /** Drain the current buffer into one batch and submit it, throwing the first typed failure. */
    void flush() {
        List<Operation> ops = drain();
        if (ops.isEmpty()) return;
        List<WriteFailure> failures = submitFailures(ops);
        if (!failures.isEmpty()) throw failures.get(0).error();
    }

    /** Stop the timer and flush everything (looping in case items arrived mid-flush). */
    void close() {
        ScheduledExecutorService ex;
        synchronized (lock) {
            closed = true;
            ex = executor;
            executor = null;
            timerStarted = false;
        }
        if (ex != null) {
            ex.shutdown(); // periodic ticks are cancelled; an in-flight flush may still finish
            try {
                ex.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        while (pending() > 0) flush();
    }

    private List<Operation> drain() {
        synchronized (lock) {
            List<Operation> ops = new ArrayList<>(buf.size());
            for (Map.Entry<String, BufferedWrite> e : buf.entrySet()) {
                BufferedWrite buffered = e.getValue();
                BigInteger delta = buffered.delta();
                int sign = delta.signum();
                if (sign == 0) continue; // add then equal subtract -> net no-op
                ops.add(sign > 0
                        ? new Operation(e.getKey(), "add", delta.toString(), buffered.idempotencyKey(), null)
                        : new Operation(e.getKey(), "subtract", delta.negate().toString(), buffered.idempotencyKey(), null));
            }
            buf.clear();
            return ops;
        }
    }

    private record BufferedWrite(BigInteger delta, String idempotencyKey) {}

    private void flushSafe() {
        List<Operation> ops = drain();
        if (ops.isEmpty()) return;
        List<WriteFailure> failures = submitFailures(ops);
        if (onError != null) failures.forEach(onError);
    }

    private List<WriteFailure> submitFailures(List<Operation> ops) {
        try {
            List<WriteFailure> failures = submit.submit(ops);
            return failures == null ? List.of() : failures;
        } catch (Throwable failure) {
            CountersException error = CountersException.normalizeBatchFailure(failure);
            List<WriteFailure> failures = new ArrayList<>(ops.size());
            for (Operation op : ops) failures.add(WriteFailure.from(op, error));
            return List.copyOf(failures);
        }
    }

    private ScheduledExecutorService executor() {
        // called under lock
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "counters-batcher");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }
}
