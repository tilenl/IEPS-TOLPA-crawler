package si.uni_lj.fri.wier.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;

/** TS-03 / TS-14: bounded headless concurrency and acquire timeout behaviour. */
class HeadlessSessionPoolTest {

    @Test
    void tryAcquireSlot_secondCallerTimesOutWhenFirstHoldsSlot() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.fetch.maxHeadlessSessions", "1");
        p.setProperty("crawler.fetch.headlessAcquireTimeoutMs", "150");
        p.setProperty("crawler.fetch.headlessCircuitOpenThreshold", "100");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        HeadlessSessionPool pool = new HeadlessSessionPool(cfg, clock);
        CountDownLatch hold = new CountDownLatch(1);
        Thread holder =
                new Thread(
                        () -> {
                            try {
                                assertTrue(pool.tryAcquireSlot());
                                hold.countDown();
                                Thread.sleep(400);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                pool.releaseSlot();
                            }
                        });
        holder.start();
        assertTrue(hold.await(5, TimeUnit.SECONDS));
        assertFalse(pool.tryAcquireSlot());
        holder.join();
        assertTrue(pool.tryAcquireSlot());
        pool.releaseSlot();
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(HeadlessSessionPoolTest.class.getResource("/keywords-valid.json").toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        return p;
    }
}
