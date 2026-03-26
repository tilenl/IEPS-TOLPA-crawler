package si.uni_lj.fri.wier.contracts;

/**
 * Rate-limiter outcome for one domain token request.
 *
 * @param delayed whether the caller must delay processing before retry.
 * @param waitNs nanoseconds to wait before retrying when delayed.
 */
public record RateLimitDecision(boolean delayed, long waitNs) {
    public static RateLimitDecision allowed() {
        return new RateLimitDecision(false, 0L);
    }

    public static RateLimitDecision delayed(long waitNs) {
        return new RateLimitDecision(true, Math.max(0L, waitNs));
    }

    public boolean isDelayed() {
        return delayed;
    }
}
