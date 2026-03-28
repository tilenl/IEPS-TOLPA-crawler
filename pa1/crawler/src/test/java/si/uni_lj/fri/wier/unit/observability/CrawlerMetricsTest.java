package si.uni_lj.fri.wier.unit.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;

class CrawlerMetricsTest {

    @Test
    void recordFrontierQueueHealth_exposesTs12QueueFields() {
        CrawlerMetrics m = new CrawlerMetrics();
        m.recordFrontierQueueHealth(5L, 120L, 900L);
        assertEquals(5L, m.overdueFrontierRowCount());
        assertEquals(120L, m.delayedQueueAgeMillis());
        assertEquals(900L, m.oldestOverdueRetryMillis());
    }

    @Test
    void recordRateLimitWait_accumulatesEventsAndMillis() {
        CrawlerMetrics m = new CrawlerMetrics();
        m.recordRateLimitWait(100L);
        m.recordRateLimitWait(50L);
        assertEquals(2L, m.rateLimitWaitEvents());
        assertEquals(150L, m.rateLimitWaitMillisTotal());
    }

    @Test
    void recordFetchLatencyMillis_incrementsBoundedHistogram() {
        CrawlerMetrics m = new CrawlerMetrics();
        m.recordFetchLatencyMillis("example.com", 50L);
        m.recordFetchLatencyMillis("example.com", 10_000L);
        long[] ex = m.fetchLatencyHistogramSnapshot().get("example.com");
        assertTrue(ex != null && ex.length == CrawlerMetrics.latencyBucketCount());
        assertTrue(ex[0] >= 1L);
        assertTrue(java.util.Arrays.stream(ex).sum() >= 2L);
    }

    @Test
    void leaseRecoveryAndBudgetCounters_increment() {
        CrawlerMetrics m = new CrawlerMetrics();
        m.recordLeaseRecoveryBatch(3);
        m.recordBudgetDropped();
        m.recordFrontierDeferred();
        assertEquals(3L, m.leaseRecoveryRowsTotal());
        assertEquals(1L, m.leaseRecoveryBatchesTotal());
        assertEquals(1L, m.budgetDroppedTotal());
        assertEquals(1L, m.frontierDeferredTotal());
    }
}
