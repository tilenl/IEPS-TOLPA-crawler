package si.uni_lj.fri.wier.observability;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Emits structured {@code CRAWLER_HEARTBEAT} on {@link RuntimeConfig#healthHeartbeatIntervalMs} (TS-15, TS-13).
 */
public final class CrawlerHeartbeatScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CrawlerHeartbeatScheduler.class);

    /** Loads queue counts for one heartbeat tick (tests may substitute). */
    @FunctionalInterface
    public interface SnapshotSource {
        PageRepository.HeartbeatQueueSnapshot load();
    }

    private final SnapshotSource snapshotSource;
    private final long intervalMs;
    private final String workerId;
    private final int workerCount;
    private final ScheduledExecutorService executor;

    public CrawlerHeartbeatScheduler(PageRepository repository, RuntimeConfig config, String workerId) {
        this(
                Objects.requireNonNull(repository, "repository")::queryHeartbeatQueueSnapshot,
                config.healthHeartbeatIntervalMs(),
                Objects.requireNonNull(workerId, "workerId"),
                config.nCrawlers());
    }

    /** Visible for tests with a stub {@link SnapshotSource}. */
    public CrawlerHeartbeatScheduler(
            SnapshotSource snapshotSource, long intervalMs, String workerId, int workerCount) {
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        this.intervalMs = intervalMs;
        this.workerId = workerId;
        this.workerCount = workerCount;
        ThreadFactory factory =
                r -> {
                    Thread t = new Thread(r, "crawler-heartbeat");
                    t.setDaemon(true);
                    return t;
                };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** Schedules the first tick immediately, then every {@code intervalMs}. */
    public void start() {
        executor.scheduleAtFixedRate(this::emitOnce, 0L, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void emitOnce() {
        try {
            PageRepository.HeartbeatQueueSnapshot s = snapshotSource.load();
            log.info(
                    "event=CRAWLER_HEARTBEAT workerId={} frontierDepth={} processingCount={} pagesTerminalTotal={}"
                            + " workerCount={}",
                    workerId,
                    s.frontierDepth(),
                    s.processingCount(),
                    s.pagesTerminalTotal(),
                    workerCount);
        } catch (Exception e) {
            log.warn("event=CRAWLER_HEARTBEAT_FAILED workerId={} message={}", workerId, e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
