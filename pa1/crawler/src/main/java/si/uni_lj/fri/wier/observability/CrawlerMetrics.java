package si.uni_lj.fri.wier.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;

/**
 * In-process counters for TS-12 / TS-15 (replaceable later with Micrometer).
 *
 * <p>Created: 2026-03.
 */
public final class CrawlerMetrics {

    private final Map<CrawlerErrorCategory, AtomicLong> failuresByCategory = new EnumMap<>(CrawlerErrorCategory.class);
    private final AtomicLong retriesScheduled = new AtomicLong();
    private final AtomicLong terminalFailures = new AtomicLong();

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
}
