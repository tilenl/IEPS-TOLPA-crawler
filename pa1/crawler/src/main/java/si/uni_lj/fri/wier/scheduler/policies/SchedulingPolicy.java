/*
 * Worker executor and identity helpers (TS-14, TS-08 operational coupling to TS-13 pool sizing).
 *
 * Callers: future TS-02 scheduler; tests.
 *
 * Created: 2026-03. Major revision: virtual-thread executor factory + worker id (TS-14).
 */

package si.uni_lj.fri.wier.scheduler.policies;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import si.uni_lj.fri.wier.config.RuntimeConfig;

/**
 * Threading policy for crawler workers: virtual threads per TS-14; JDBC must not be held across fetch/parse.
 *
 * <p><b>JDBC scope:</b> open a {@link javax.sql.DataSource} connection only around queue/storage work (claim,
 * persist). Do not hold a connection while blocked in {@link si.uni_lj.fri.wier.downloader.fetch.HttpFetcher} or
 * Selenium — {@link RuntimeConfig#dbPoolSize()} is validated to be at least {@code nCrawlers + 1} so the pool
 * is not exhausted while workers wait on network I/O.
 */
public final class SchedulingPolicy {

    private SchedulingPolicy() {}

    /**
     * Executor backed by Java 21 virtual threads — one runnable per worker loop is typical (TS-14).
     *
     * @return unbounded virtual-thread executor; caller must {@link ExecutorService#shutdown()} on graceful stop
     */
    public static ExecutorService newVirtualThreadWorkerExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Stable identifier for one worker process lifetime (TS-14); use for {@code claimed_by} and logs when TS-02
     * wires the pipeline.
     */
    public static String newWorkerId() {
        return "worker-" + UUID.randomUUID();
    }

    /**
     * Documents the TS-13 invariant enforced by {@link RuntimeConfig#validate()}: pool must cover all workers
     * plus headroom.
     */
    public static boolean poolSizeSatisfiesWorkers(RuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        return config.dbPoolSize() >= config.nCrawlers() + 1;
    }
}
