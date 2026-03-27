package si.uni_lj.fri.wier.error;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import si.uni_lj.fri.wier.config.RuntimeConfig;

/**
 * TS-12 error taxonomy, retry budgets, and delay policy (pure decision logic).
 *
 * <p>Budget rule: for fetch-stage categories, terminal when {@code attempt_count >= N} where {@code N}
 * comes from {@link RuntimeConfig} (or fixed matrix values). For {@link CrawlerErrorCategory#PARSER_FAILURE},
 * fetch attempts do not consume the parser budget; terminal when {@code parser_retry_count >= 1} (one parser
 * reschedule per TS-12 matrix).
 *
 * <p>Created: 2026-03. Major revisions: initial TS-12 implementation.
 */
public final class RecoveryPolicy {

    /** TS-12 matrix: {@code ROBOTS_TRANSIENT} max attempts (threshold on {@code attempt_count}). */
    public static final int ROBOTS_TRANSIENT_ATTEMPT_THRESHOLD = 3;

    /**
     * TS-12 matrix: parser-stage reschedule budget. Reschedule allowed while {@code parser_retry_count <
     * this}; after one parser reschedule the count reaches this value and the next parser failure is
     * terminal.
     */
    public static final int PARSER_STAGE_RESCHEDULE_THRESHOLD = 1;

    private RecoveryPolicy() {}

    /**
     * @return whether TS-12 classifies the category as retryable at all (before budget checks).
     */
    public static boolean isRetryable(CrawlerErrorCategory category) {
        return switch (category) {
            case INVALID_URL,
                    ROBOTS_DISALLOWED,
                    FETCH_HTTP_CLIENT,
                    DB_CONSTRAINT -> false;
            case ROBOTS_TRANSIENT,
                    FETCH_TIMEOUT,
                    FETCH_HTTP_OVERLOAD,
                    FETCH_CAPACITY_EXHAUSTED,
                    PARSER_FAILURE,
                    DB_TRANSIENT -> true;
        };
    }

    /**
     * Max-attempt threshold on {@code attempt_count} for fetch-stage categories (TS-12 / TS-13).
     *
     * <p>Not used for {@link CrawlerErrorCategory#PARSER_FAILURE}; use {@link
     * #PARSER_STAGE_RESCHEDULE_THRESHOLD} against {@code parser_retry_count} instead.
     */
    public static int maxAttemptThreshold(CrawlerErrorCategory category, RuntimeConfig config) {
        return switch (category) {
            case FETCH_TIMEOUT -> config.retryMaxAttemptsFetchTimeout();
            case FETCH_HTTP_OVERLOAD -> config.retryMaxAttemptsFetchOverload();
            case FETCH_CAPACITY_EXHAUSTED -> config.retryMaxAttemptsFetchCapacity();
            case DB_TRANSIENT -> config.retryMaxAttemptsDbTransient();
            case ROBOTS_TRANSIENT -> ROBOTS_TRANSIENT_ATTEMPT_THRESHOLD;
            case PARSER_FAILURE -> PARSER_STAGE_RESCHEDULE_THRESHOLD;
            case INVALID_URL,
                    ROBOTS_DISALLOWED,
                    FETCH_HTTP_CLIENT,
                    DB_CONSTRAINT -> 0;
        };
    }

    /**
     * Decide the next durable queue action for a failed processing attempt on a leased row.
     *
     * @param attemptCount persisted {@code attempt_count} at claim time
     * @param parserRetryCount persisted {@code parser_retry_count} at claim time
     */
    public static RecoveryDecision decide(
            CrawlerErrorCategory category,
            int attemptCount,
            int parserRetryCount,
            RuntimeConfig config,
            Clock clock) {
        if (!isRetryable(category)) {
            return new RecoveryDecision.Terminal();
        }
        // Parser budget is independent of fetch-stage attempt_count (TS-12 normative).
        if (category == CrawlerErrorCategory.PARSER_FAILURE) {
            if (parserRetryCount >= PARSER_STAGE_RESCHEDULE_THRESHOLD) {
                return new RecoveryDecision.Terminal();
            }
        } else {
            int max = maxAttemptThreshold(category, config);
            if (attemptCount >= max) {
                return new RecoveryDecision.Terminal();
            }
        }
        return new RecoveryDecision.Reschedule(computeNextAttemptAt(category, attemptCount, config, clock));
    }

    /**
     * Computes {@code next_attempt_at} for a reschedule row (TS-12 delay matrix, pragmatic until TS-08
     * per-domain overload state exists).
     */
    public static Instant computeNextAttemptAt(
            CrawlerErrorCategory category, int attemptCount, RuntimeConfig config, Clock clock) {
        Instant now = clock.instant();
        long jitter = jitterMillis(config.retryJitterMs());
        return switch (category) {
            case FETCH_TIMEOUT, DB_TRANSIENT -> exponentialBackoff(
                    now, config.recoveryPathBaseBackoffMs(), attemptCount, config.rateLimitMaxBackoffMs(), jitter);
            case FETCH_HTTP_OVERLOAD ->
                    // NOTE: TS-08 domain backoff is not persisted yet; use capped global exponential delay.
                    exponentialBackoff(
                            now,
                            Math.max(config.rateLimitMinDelayMs(), config.recoveryPathBaseBackoffMs()),
                            attemptCount,
                            config.rateLimitMaxBackoffMs(),
                            jitter);
            case FETCH_CAPACITY_EXHAUSTED ->
                    shortDelay(now, config.recoveryPathBaseBackoffMs(), attemptCount, Duration.ofSeconds(30), jitter);
            case ROBOTS_TRANSIENT ->
                    now.plus(Duration.ofMinutes(config.robotsTemporaryDenyRetryMinutes())).plusMillis(jitter);
            case PARSER_FAILURE -> now.plus(Duration.ofSeconds(2)).plusMillis(jitter);
            default -> now.plusMillis(Math.max(1, config.recoveryPathBaseBackoffMs()) + jitter);
        };
    }

    private static Instant exponentialBackoff(
            Instant now, int baseBackoffMs, int attemptCount, int capMs, long jitterMs) {
        int exp = Math.min(attemptCount, 20);
        long mult = 1L << exp;
        long delayMs = (long) baseBackoffMs * mult;
        long capped = Math.min(delayMs, capMs);
        return now.plusMillis(Math.max(1, capped) + jitterMs);
    }

    private static Instant shortDelay(
            Instant now, int baseBackoffMs, int attemptCount, Duration maxDelay, long jitterMs) {
        long linear = (long) baseBackoffMs * (attemptCount + 1);
        long capMs = maxDelay.toMillis();
        long delayMs = Math.min(linear, capMs);
        return now.plusMillis(Math.max(1, delayMs) + jitterMs);
    }

    private static long jitterMillis(int maxJitterMs) {
        if (maxJitterMs <= 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(maxJitterMs + 1L);
    }
}
