package dev.counters.sdk;

/**
 * One coalesced fire-and-forget write whose outcome failed or is unknown. {@code delta} is a signed,
 * arbitrary-precision decimal string: increments are positive and decrements are negative.
 * {@code member} is nullable because the current Java write buffer only batches counter writes.
 */
public record WriteFailure(
        String counterKey,
        String delta,
        String member,
        String idempotencyKey,
        CountersException error) {

    static WriteFailure from(Operation operation, CountersException error) {
        String amount = operation.amount();
        String delta = "subtract".equals(operation.operation()) && !"0".equals(amount)
                ? "-" + amount
                : amount;
        return new WriteFailure(
                operation.counterKey(), delta, null, operation.idempotencyKey(), error);
    }
}
