package si.uni_lj.fri.wier.unit.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.observability.CrawlerHeartbeatScheduler;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

class CrawlerHeartbeatSchedulerTest {

    @Test
    void start_invokesSnapshotSourceOnSchedule() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CrawlerHeartbeatScheduler scheduler =
                new CrawlerHeartbeatScheduler(
                        () -> {
                            loads.incrementAndGet();
                            return new PageRepository.HeartbeatQueueSnapshot(1L, 2L, 3L, 0L);
                        },
                        20L,
                        () -> "test-worker",
                        4,
                        null);
        scheduler.start();
        Thread.sleep(120);
        scheduler.close();
        assertTrue(loads.get() >= 2, "expected at least two heartbeat ticks");
    }
}
