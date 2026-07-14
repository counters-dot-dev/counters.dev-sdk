package dev.counters.sdk;

/** Read parameters for a member snapshot. */
public record MemberGetParams(Long epoch, String order) {
    public MemberGetParams() {
        this(null, null);
    }
}
