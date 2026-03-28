package si.uni_lj.fri.wier.unit.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.observability.CrawlerHeartbeatScheduler;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

class CrawlerHeartbeatSchedulerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger heartbeatLogger;

    @BeforeEach
    void attachHeartbeatLogAppender() {
        heartbeatLogger = (Logger) LoggerFactory.getLogger(CrawlerHeartbeatScheduler.class);
        appender = new ListAppender<>();
        appender.start();
        heartbeatLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        heartbeatLogger.detachAppender(appender);
        appender.stop();
    }

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

    @Test
    void start_logsFrontierDepthAndProcessingCount_whenOptionalMetricsPresent() throws Exception {
        CrawlerMetrics metrics = new CrawlerMetrics();
        metrics.setDbPoolCapacity(10);
        metrics.setHeadlessPoolCapacity(2);
        CrawlerHeartbeatScheduler scheduler =
                new CrawlerHeartbeatScheduler(
                        () -> new PageRepository.HeartbeatQueueSnapshot(7L, 3L, 100L, 12L),
                        40L,
                        () -> "lease-owner-1",
                        2,
                        null,
                        metrics);
        scheduler.start();
        Thread.sleep(100);
        scheduler.close();
        assertTrue(
                appender.list.stream()
                        .anyMatch(
                                e -> {
                                    String msg = e.getFormattedMessage();
                                    return msg.contains("event=CRAWLER_HEARTBEAT")
                                            && msg.contains("frontierDepth=7")
                                            && msg.contains("processingCount=3")
                                            && msg.contains("workerId=lease-owner-1")
                                            && msg.contains("dbPoolUtilizationPermille=");
                                }),
                "expected TS-15 MUST heartbeat fields in log output");
    }

    @Test
    void start_skipsTickWhenWorkerIdBlank() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CrawlerHeartbeatScheduler scheduler =
                new CrawlerHeartbeatScheduler(
                        () -> {
                            loads.incrementAndGet();
                            return new PageRepository.HeartbeatQueueSnapshot(1L, 1L, 1L, 0L);
                        },
                        25L,
                        () -> "",
                        1,
                        null,
                        new CrawlerMetrics());
        scheduler.start();
        Thread.sleep(80);
        scheduler.close();
        assertEquals(0, loads.get(), "snapshot must not load until workerId is non-blank");
    }
}
