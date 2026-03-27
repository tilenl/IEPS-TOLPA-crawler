package si.uni_lj.fri.wier.error;

/**
 * TS-12 error taxonomy: stable names persisted in {@code crawldb.page.last_error_category} and used for
 * retry/terminal policy.
 *
 * <p>Each constant maps to a row in the recovery matrix (retryable flag, max attempts, delay style). See
 * {@link RecoveryPolicy} for enforcement.
 *
 * <p>Placed in the worker pipeline by fetch/parse/robots/DB layers (TS-02–TS-06, TS-10). Created: 2026-03.
 */
public enum CrawlerErrorCategory {
    /** Malformed URL or canonicalization failure; non-retryable. */
    INVALID_URL,
    /** Stable robots disallow; non-retryable. */
    ROBOTS_DISALLOWED,
    /** Robots fetch or temporary deny window; retryable with fixed matrix cap. */
    ROBOTS_TRANSIENT,
    /** Connect/read timeout; retryable with exponential delay. */
    FETCH_TIMEOUT,
    /** HTTP 429/503 style overload; retryable with capped backoff. */
    FETCH_HTTP_OVERLOAD,
    /** Headless slot not acquired; retryable with short delay (TS-03). */
    FETCH_CAPACITY_EXHAUSTED,
    /** Stable 4xx except 429; non-retryable. */
    FETCH_HTTP_CLIENT,
    /** Parse/extract failed after a successful fetch handoff; retryable once on parser budget only. */
    PARSER_FAILURE,
    /** Transient SQL/network; retryable. */
    DB_TRANSIENT,
    /** Integrity violation; non-retryable, signals logic bug. */
    DB_CONSTRAINT
}
