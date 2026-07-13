package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BatcherTest {

    private static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }

    @Test
    void coalescesAddsIntoOneOperation() {
        List<List<Operation>> captured = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(captured::add, 1000, 0, null);
        b.enqueue("c", big(1));
        b.enqueue("c", big(2));
        b.enqueue("c", big(3));
        b.flush();

        assertEquals(1, captured.size(), "expected one batch");
        assertEquals(1, captured.get(0).size(), "expected one coalesced op");
        Operation op = captured.get(0).get(0);
        assertEquals("c", op.counterKey());
        assertEquals("add", op.op());
        assertEquals("6", op.amount());
        assertNotNull(op.idempotencyKey());
    }

    @Test
    void netZeroIsDroppedAndNetNegativeBecomesSubtract() {
        List<List<Operation>> captured = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(captured::add, 1000, 0, null);
        b.enqueue("a", big(2));
        b.enqueue("a", big(-9)); // net -7 -> subtract 7
        b.enqueue("z", big(5));
        b.enqueue("z", big(-5)); // net 0 -> dropped
        b.flush();

        assertEquals(1, captured.size());
        assertEquals(1, captured.get(0).size(), "z should be dropped: " + captured);
        Operation op = captured.get(0).get(0);
        assertEquals("a", op.counterKey());
        assertEquals("subtract", op.op());
        assertEquals("7", op.amount());
    }

    @Test
    void flushDrainsBufferAndSkipsEmptySubmit() {
        List<List<Operation>> captured = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(captured::add, 1000, 0, null);
        b.enqueue("a", big(1));
        assertEquals(1, b.pending());
        b.flush();
        assertEquals(0, b.pending(), "flush must drain the buffer");
        b.flush(); // nothing buffered -> no submit
        assertEquals(1, captured.size());
    }

    @Test
    void maxBatchSizeTriggersFlush() throws InterruptedException {
        CountDownLatch flushed = new CountDownLatch(1);
        AtomicReference<List<Operation>> got = new AtomicReference<>();
        Batcher b = new Batcher(ops -> {
            got.set(ops);
            flushed.countDown();
        }, 2, 0, null);

        b.enqueue("a", big(1));
        b.enqueue("b", big(1)); // size hits maxBatchSize=2 -> async flush

        assertTrue(flushed.await(2, TimeUnit.SECONDS), "flush did not happen at maxBatchSize");
        assertEquals(2, got.get().size());
        assertEquals(0, b.pending());
    }

    @Test
    void intervalTimerFlushesInBackground() throws InterruptedException {
        CountDownLatch flushed = new CountDownLatch(1);
        Batcher b = new Batcher(ops -> flushed.countDown(), 1000, 20, null);
        b.enqueue("a", big(1));
        assertTrue(flushed.await(2, TimeUnit.SECONDS), "timer flush did not happen");
        b.close();
    }

    @Test
    void backgroundFlushErrorsGoToOnError() throws InterruptedException {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        Batcher b = new Batcher(ops -> {
            throw new CountersApiException(500, "boom");
        }, 1000, 20, t -> {
            seen.set(t);
            failed.countDown();
        });
        b.enqueue("a", big(1));
        assertTrue(failed.await(2, TimeUnit.SECONDS), "onError was not invoked");
        assertTrue(seen.get() instanceof CountersApiException);
        assertEquals(0, b.pending(), "buffer is drained before submit, even when submit fails");
    }

    @Test
    void closeStopsTimerAndFlushesRemainder() {
        List<List<Operation>> captured = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(captured::add, 1000, 60_000, null);
        b.enqueue("a", big(4));
        b.close(); // timer never fired; close must flush
        assertEquals(1, captured.size());
        assertEquals("4", captured.get(0).get(0).amount());
        assertEquals(0, b.pending());
    }

    @Test
    void enqueueAfterCloseThrows() {
        Batcher b = new Batcher(ops -> {}, 1000, 60_000, null);
        b.enqueue("a", big(1));
        b.close();
        assertThrows(CountersException.class, () -> b.enqueue("a", big(1)));
        assertEquals(0, b.pending()); // rejected write did not land in the buffer
    }
}
