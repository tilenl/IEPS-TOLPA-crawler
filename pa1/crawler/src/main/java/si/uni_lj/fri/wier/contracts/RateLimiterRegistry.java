package si.uni_lj.fri.wier.contracts;

public interface RateLimiterRegistry {
    Contracts.RateLimitDecision tryAcquire(String domain);
}

