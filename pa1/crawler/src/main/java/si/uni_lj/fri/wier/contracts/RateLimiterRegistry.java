package si.uni_lj.fri.wier.contracts;

/** Per-domain rate-limiter registry contract. */
public interface RateLimiterRegistry {
    RateLimitDecision tryAcquire(String domain);

    /**
     * Optional hook for HTTP status-driven overload backoff (TS-08). Implementations that track 429/503 should
     * override; default is no-op so test stubs need not implement it.
     *
     * @param domain lower-case host key
     * @param statusCode HTTP response status from a content or robots fetch
     */
    default void recordHttpResponse(String domain, int statusCode) {
        // default no-op
    }
}
