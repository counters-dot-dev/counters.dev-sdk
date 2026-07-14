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

    private final Consumer<List<Operation>> submit;
    private final int maxBatchSize;
    private final long intervalMillis;
    private final Consumer<Throwable> onError;

    private final Object lock = new Object();
    private final Map<String, BigInteger> buf = new LinkedHashMap<>();
    private ScheduledExecutorService executor; // lazily created; daemon thread
    private boolean timerStarted;
    private boolean closed;

    Batcher(Consumer<List<Operation>> submit, int maxBatchSize, long intervalMillis, Consumer<Throwable> onError) {
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
            if (closed) throw new CountersException("cannot enqueue on a closed client");
            buf.merge(counterKey, delta, BigInteger::add);
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

    /** Drain the current buffer into one batch and submit it (throws whatever the submit fn throws). */
    void flush() {
        List<Operation> ops = drain();
        if (ops.isEmpty()) return;
        submit.accept(ops);
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
            for (Map.Entry<String, BigInteger> e : buf.entrySet()) {
                BigInteger delta = e.getValue();
                int sign = delta.signum();
                if (sign == 0) continue; // add then equal subtract -> net no-op
                ops.add(sign > 0
                        ? new Operation(e.getKey(), "add", delta.toString(), Idempotency.newKey(), null)
                        : new Operation(e.getKey(), "subtract", delta.negate().toString(), Idempotency.newKey(), null));
            }
            buf.clear();
            return ops;
        }
    }

    private void flushSafe() {
        try {
            flush();
        } catch (Throwable t) {
            if (onError != null) onError.accept(t);
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
