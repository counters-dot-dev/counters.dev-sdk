package dev.counters.sdk;

import java.math.BigInteger;

/**
 * A typed handle to a single member of a counter's board. Member writes are immediate; they are never
 * routed through the counter write buffer.
 */
public final class MemberHandle implements ReadOnlyMemberHandle {

    private final CountersClient client;
    private final String counterKey;
    private final String member;

    MemberHandle(CountersClient client, String counterKey, String member) {
        this.client = client;
        this.counterKey = counterKey;
        this.member = member;
    }

    /** The validated counter key. */
    public String counterKey() {
        return counterKey;
    }

    /** The validated member key. */
    public String member() {
        return member;
    }

    /** This member's rank, percentile, and standing value. */
    public MemberSnapshot get() {
        return get(null);
    }

    /** This member's rank, percentile, and standing value. */
    public MemberSnapshot get(MemberGetParams params) {
        return client.getMember(counterKey, member, params);
    }

    /** Remove this member from the current board. */
    public MemberRemoved remove() {
        return client.removeMember(counterKey, member);
    }

    /** Add a non-negative delta to this member (sum board). */
    public MemberValue add(long amount) {
        return add(amount, null);
    }

    /** Add a non-negative delta to this member (sum board). */
    public MemberValue add(String amount) {
        return add(amount, null);
    }

    /** Add a non-negative delta to this member (sum board). */
    public MemberValue add(BigInteger amount) {
        return add(amount, null);
    }

    /** Add a non-negative delta to this member (sum board). */
    public MemberValue add(long amount, MemberWriteOptions opts) {
        return client.applyMember(counterKey, member, "add", Validation.toAmount(amount), opts);
    }

    /** Add a non-negative delta to this member (sum board). */
    public MemberValue add(String amount, MemberWriteOptions opts) {
        return client.applyMember(counterKey, member, "add", Validation.toAmount(amount), opts);
    }

    /** Add a non-negative delta to this member (sum board). */
    public MemberValue add(BigInteger amount, MemberWriteOptions opts) {
        return client.applyMember(counterKey, member, "add", Validation.toAmount(amount), opts);
    }

    /** Subtract a non-negative delta from this member (sum board; the member may go negative). */
    public MemberValue subtract(long amount) {
        return subtract(amount, null);
    }

    /** Subtract a non-negative delta from this member (sum board; the member may go negative). */
    public MemberValue subtract(String amount) {
        return subtract(amount, null);
    }

    /** Subtract a non-negative delta from this member (sum board; the member may go negative). */
    public MemberValue subtract(BigInteger amount) {
        return subtract(amount, null);
    }

    /** Subtract a non-negative delta from this member (sum board; the member may go negative). */
    public MemberValue subtract(long amount, MemberWriteOptions opts) {
        return client.applyMember(counterKey, member, "subtract", Validation.toAmount(amount), opts);
    }

    /** Subtract a non-negative delta from this member (sum board; the member may go negative). */
    public MemberValue subtract(String amount, MemberWriteOptions opts) {
        return client.applyMember(counterKey, member, "subtract", Validation.toAmount(amount), opts);
    }

    /** Subtract a non-negative delta from this member (sum board; the member may go negative). */
    public MemberValue subtract(BigInteger amount, MemberWriteOptions opts) {
        return client.applyMember(counterKey, member, "subtract", Validation.toAmount(amount), opts);
    }

    /** Submit a signed score to a score board. */
    public MemberValue submit(long value) {
        return submit(value, null);
    }

    /** Submit a signed score to a score board. */
    public MemberValue submit(String value) {
        return submit(value, null);
    }

    /** Submit a signed score to a score board. */
    public MemberValue submit(BigInteger value) {
        return submit(value, null);
    }

    /** Submit a signed score to a score board. */
    public MemberValue submit(long value, SubmitOptions opts) {
        return client.submitMember(counterKey, member, Validation.toValue(value), opts);
    }

    /** Submit a signed score to a score board. */
    public MemberValue submit(String value, SubmitOptions opts) {
        return client.submitMember(counterKey, member, Validation.toValue(value), opts);
    }

    /** Submit a signed score to a score board. */
    public MemberValue submit(BigInteger value, SubmitOptions opts) {
        return client.submitMember(counterKey, member, Validation.toValue(value), opts);
    }
}
