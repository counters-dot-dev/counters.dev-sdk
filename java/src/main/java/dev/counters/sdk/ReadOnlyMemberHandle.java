package dev.counters.sdk;

/** Read-only operations for one member of a counter's board. */
public interface ReadOnlyMemberHandle {

    /** The validated counter key. */
    String counterKey();

    /** The validated member key. */
    String member();

    /** This member's rank, percentile, and standing value. */
    MemberSnapshot get();

    /** This member's rank, percentile, and standing value. */
    MemberSnapshot get(MemberGetParams params);
}
