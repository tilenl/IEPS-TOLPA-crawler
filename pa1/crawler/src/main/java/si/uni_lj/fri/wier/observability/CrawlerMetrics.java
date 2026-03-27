package si.uni_lj.fri.wier.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;

/**
 * In-process counters for TS-12 / TS-15 (replaceable later with Micrometer).
 *
 * <p>Queue-health fields ({@link #delayedQueueAgeMillis()}, {@link #oldestOverdueRetryMillis()}) are updated
 * from {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#sampleFrontierOverdueHealth()} via
 * {@link si.uni_lj.fri.wier.storage.frontier.FrontierStore} on claim/reschedule paths.
 *
 * <p>Created: 2026-03.
 */
public final class CrawlerMetrics {

    private final Map<CrawlerErrorCategory, AtomicLong> failuresByCategory = new EnumMap<>(CrawlerErrorCategory.class);
    private final AtomicLong retriesScheduled = new AtomicLong();
    private final AtomicLong terminalFailures = new AtomicLong();

    /** Average delay (ms) of eligible {@code FRONTIER} rows past {@code next_attempt_at} (TS-12 delayed queue age). */
    private final AtomicLong delayedQueueAgeMillis = new AtomicLong();

    /** Longest wait (ms) among those rows (TS-12 oldest overdue retry). */
    private final AtomicLong oldestOverdueRetryMillis = new AtomicLong();

    private final AtomicLong overdueFrontierRowCount = new AtomicLong();

    public CrawlerMetrics() {
        for (CrawlerErrorCategory c : CrawlerErrorCategory.values()) {
            failuresByCategory.put(c, new AtomicLong());
        }
    }

    /** Counts each classified failure entering {@link si.uni_lj.fri.wier.error.ProcessingFailureHandler}. */
    public void recordFailure(CrawlerErrorCategory category) {
        failuresByCategory.get(category).incrementAndGet();
    }

    public void recordRetryScheduled() {
        retriesScheduled.incrementAndGet();
    }

    public void recordTerminalFailure() {
        terminalFailures.incrementAndGet();
    }

    public long failureCount(CrawlerErrorCategory category) {
        return failuresByCategory.get(category).get();
    }

    public long retryScheduledCount() {
        return retriesScheduled.get();
    }

    public long terminalFailureCount() {
        return terminalFailures.get();
    }

    /**
     * Refreshes TS-12 frontier queue observability from a DB snapshot (see {@link
     * si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#sampleFrontierOverdueHealth()}).
     */
    public void recordFrontierQueueHealth(
            long overdueFrontierCount, long avgOverdueMillis, long oldestOverdueMillis) {
        overdueFrontierRowCount.set(overdueFrontierCount);
        delayedQueueAgeMillis.set(avgOverdueMillis);
        oldestOverdueRetryMillis.set(oldestOverdueMillis);
    }

    /** Eligible {@code FRONTIER} row count used in the last {@link #recordFrontierQueueHealth} refresh. */
    public long overdueFrontierRowCount() {
        return overdueFrontierRowCount.get();
    }

    /** Milliseconds: average {@code now() - next_attempt_at} among overdue frontier rows (0 if none). */
    public long delayedQueueAgeMillis() {
        return delayedQueueAgeMillis.get();
    }

    /** Milliseconds: max {@code now() - next_attempt_at} among overdue frontier rows (0 if none). */
    public long oldestOverdueRetryMillis() {
        return oldestOverdueRetryMillis.get();
    }
}
