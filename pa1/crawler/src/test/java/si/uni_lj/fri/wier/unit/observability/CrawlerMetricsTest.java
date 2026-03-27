package si.uni_lj.fri.wier.unit.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
