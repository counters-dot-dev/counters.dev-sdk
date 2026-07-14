package dev.counters.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
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
        Batcher b = new Batcher(ops -> {
            captured.add(ops);
            return List.of();
        }, 1000, 0, null);
        b.enqueue("c", big(1));
        b.enqueue("c", big(2));
        b.enqueue("c", big(3));
        b.flush();

        assertEquals(1, captured.size(), "expected one batch");
        assertEquals(1, captured.get(0).size(), "expected one coalesced op");
        Operation op = captured.get(0).get(0);
        assertEquals("c", op.counterKey());
        assertEquals("add", op.operation());
        assertEquals("6", op.amount());
        assertNotNull(op.idempotencyKey());
    }

    @Test
    void netZeroIsDroppedAndNetNegativeBecomesSubtract() {
        List<List<Operation>> captured = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(ops -> {
            captured.add(ops);
            return List.of();
        }, 1000, 0, null);
        b.enqueue("a", big(2));
        b.enqueue("a", big(-9)); // net -7 -> subtract 7
        b.enqueue("z", big(5));
        b.enqueue("z", big(-5)); // net 0 -> dropped
        b.flush();

        assertEquals(1, captured.size());
        assertEquals(1, captured.get(0).size(), "z should be dropped: " + captured);
        Operation op = captured.get(0).get(0);
        assertEquals("a", op.counterKey());
        assertEquals("subtract", op.operation());
        assertEquals("7", op.amount());
    }

    @Test
    void flushDrainsBufferAndSkipsEmptySubmit() {
        List<List<Operation>> captured = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(ops -> {
            captured.add(ops);
            return List.of();
        }, 1000, 0, null);
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
            return List.of();
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
        Batcher b = new Batcher(ops -> {
            flushed.countDown();
            return List.of();
        }, 1000, 20, null);
        b.enqueue("a", big(1));
        assertTrue(flushed.await(2, TimeUnit.SECONDS), "timer flush did not happen");
        b.close();
    }

    @Test
    void zeroIntervalDisablesTheTimer() {
        Batcher b = new Batcher(ops -> List.of(), 1000, 0, null);
        b.enqueue("a", big(1));

        assertFalse(b.isTimerStarted(), "an explicit zero interval must not arm a default timer");
        assertEquals(1, b.pending(), "the write remains pending until a size/manual/close flush");
        b.close();
    }

    @Test
    void backgroundFlushErrorsGoToOnError() throws InterruptedException {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<WriteFailure> seen = new AtomicReference<>();
        Batcher b = new Batcher(ops -> {
            throw new CountersApiException(500, "boom");
        }, 1000, 20, t -> {
            seen.set(t);
            failed.countDown();
        });
        b.enqueue("a", big(1));
        assertTrue(failed.await(2, TimeUnit.SECONDS), "onError was not invoked");
        assertEquals("a", seen.get().counterKey());
        assertEquals("1", seen.get().delta());
        assertEquals(null, seen.get().member());
        assertNotNull(seen.get().idempotencyKey());
        assertTrue(seen.get().error() instanceof CountersApiException);
        assertEquals(0, b.pending(), "buffer is drained before submit, even when submit fails");
    }

    @Test
    void backgroundFlushNormalizesUnexpectedFailuresToTypedTransportErrors() throws InterruptedException {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<WriteFailure> seen = new AtomicReference<>();
        IllegalStateException cause = new IllegalStateException("unexpected");
        Batcher b = new Batcher(ops -> {
            throw cause;
        }, 1, 0, error -> {
            seen.set(error);
            failed.countDown();
        });

        b.enqueue("a", big(1));

        assertTrue(failed.await(2, TimeUnit.SECONDS), "onError was not invoked");
        assertEquals("a", seen.get().counterKey());
        assertEquals("1", seen.get().delta());
        assertTrue(seen.get().error() instanceof CountersTransportException);
        assertEquals(cause, seen.get().error().getCause());
    }

    @Test
    void malformedBatchResultsAreValidationFailures() {
        List<Operation> operations = List.of(
                new Operation("a", "add", "1", "key-a", null),
                new Operation("b", "subtract", "3", "key-b", null));
        Map<String, Object> appliedA = Map.of("counterKey", "a", "status", "applied");
        Map<String, Object> appliedB = Map.of("counterKey", "b", "status", "deduplicated");

        List<Object> malformedResponses = List.of(
                Map.of(),
                Map.of("results", "not-an-array"),
                Map.of("results", List.of(appliedA)),
                Map.of("results", List.of(appliedA, "not-an-object")),
                Map.of("results", List.of(
                        appliedA,
                        Map.of("status", "applied"))),
                Map.of("results", List.of(
                        Map.of("counterKey", "a", "status", "applied"),
                        Map.of("counterKey", "a", "status", "deduplicated"))),
                Map.of("results", List.of(
                        appliedA,
                        Map.of("counterKey", "unknown", "status", "applied"))),
                Map.of("results", List.of(
                        appliedA,
                        Map.of("counterKey", " ", "status", "applied"))),
                Map.of("results", List.of(
                        appliedA,
                        Map.of("counterKey", "b", "status", "unknown"))));

        for (Object response : malformedResponses) {
            assertThrows(CountersValidationException.class,
                    () -> CountersClient.checkBatchResults(response, operations), response::toString);
        }
        assertEquals(List.of(), CountersClient.checkBatchResults(
                Map.of("results", List.of(appliedA, appliedB)), operations));
    }

    @Test
    void validBatchErrorsRetainTheirApiOrValidationTaxonomy() {
        Operation operation = new Operation("capped", "add", "7", "key-capped", null);

        List<WriteFailure> apiFailures = CountersClient.checkBatchResults(
                Map.of("results", List.of(Map.of(
                        "counterKey", "capped",
                        "status", "error",
                        "error", Map.of("title", "quota exceeded", "status", 403)))),
                List.of(operation));
        assertEquals(1, apiFailures.size());
        assertEquals("capped", apiFailures.get(0).counterKey());
        assertEquals("7", apiFailures.get(0).delta());
        assertEquals("key-capped", apiFailures.get(0).idempotencyKey());
        assertTrue(apiFailures.get(0).error() instanceof CountersApiException);
        assertEquals(403, ((CountersApiException) apiFailures.get(0).error()).status());

        List<WriteFailure> validationFailures = CountersClient.checkBatchResults(
                Map.of("results", List.of(Map.of(
                        "counterKey", "capped",
                        "status", "error",
                        "error", Map.of("title", "quota exceeded")))),
                List.of(operation));
        assertEquals(1, validationFailures.size());
        assertTrue(validationFailures.get(0).error() instanceof CountersValidationException);

        for (Object invalidStatus : List.of(
                403.5,
                new BigInteger("999999999999999999999999999999999999"),
                0,
                99,
                600)) {
            List<WriteFailure> invalidStatusFailures = CountersClient.checkBatchResults(
                    Map.of("results", List.of(Map.of(
                            "counterKey", "capped",
                            "status", "error",
                            "error", Map.of("title", "bad status", "status", invalidStatus)))),
                    List.of(operation));
            assertEquals(1, invalidStatusFailures.size());
            assertTrue(invalidStatusFailures.get(0).error() instanceof CountersValidationException,
                    "invalid per-op status leaked into API taxonomy: " + invalidStatus);
        }
    }

    @Test
    void malformedBatchResponseFansOutEverySubmittedWriteIdentity() throws InterruptedException {
        CountDownLatch failed = new CountDownLatch(2);
        AtomicReference<List<Operation>> submitted = new AtomicReference<>();
        List<WriteFailure> failures = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(operations -> {
            submitted.set(operations);
            return CountersClient.checkBatchResults(Map.of("results", List.of()), operations);
        }, 2, 0, failure -> {
            failures.add(failure);
            failed.countDown();
        });

        b.enqueue("a", big(1));
        b.enqueue("b", big(-3));

        assertTrue(failed.await(2, TimeUnit.SECONDS), "malformed response did not fan out both identities");
        assertEquals(2, failures.size());
        assertEquals("a", failures.get(0).counterKey());
        assertEquals("1", failures.get(0).delta());
        assertEquals(submitted.get().get(0).idempotencyKey(), failures.get(0).idempotencyKey());
        assertTrue(failures.get(0).error() instanceof CountersValidationException);
        assertEquals("b", failures.get(1).counterKey());
        assertEquals("-3", failures.get(1).delta());
        assertEquals(submitted.get().get(1).idempotencyKey(), failures.get(1).idempotencyKey());
        assertTrue(failures.get(1).error() instanceof CountersValidationException);
        b.close();
    }

    @Test
    void closeStopsTimerAndFlushesRemainder() {
        List<List<Operation>> captured = new CopyOnWriteArrayList<>();
        Batcher b = new Batcher(ops -> {
            captured.add(ops);
            return List.of();
        }, 1000, 60_000, null);
        b.enqueue("a", big(4));
        b.close(); // timer never fired; close must flush
        assertEquals(1, captured.size());
        assertEquals("4", captured.get(0).get(0).amount());
        assertEquals(0, b.pending());
    }

    @Test
    void enqueueAfterCloseThrows() {
        Batcher b = new Batcher(ops -> List.of(), 1000, 60_000, null);
        b.enqueue("a", big(1));
        b.close();
        assertThrows(CountersValidationException.class, () -> b.enqueue("a", big(1)));
        assertEquals(0, b.pending()); // rejected write did not land in the buffer
    }
}
