package si.uni_lj.fri.wier.contracts;

/** Per-domain rate-limiter registry contract. */
public interface RateLimiterRegistry {
    RateLimitDecision tryAcquire(String domain);
}
