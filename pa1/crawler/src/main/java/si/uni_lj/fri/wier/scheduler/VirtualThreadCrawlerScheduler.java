/*
 * TS-02 / TS-14: virtual-thread worker pool plus termination supervisor (grace window on empty frontier).
 *
 * Callers: {@link si.uni_lj.fri.wier.app.PreferentialCrawler}. Natural completion shuts down the executor; external
 * stop uses {@link #stopGracefully(Duration)} (e.g. JVM shutdown hook).
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.downloader.worker.WorkerLoop;
import si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Runs N {@link WorkerLoop} tasks and one supervisor that implements TS-02 {@code terminationConditionMet}.
 */
public final class VirtualThreadCrawlerScheduler implements si.uni_lj.fri.wier.contracts.Scheduler {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadCrawlerScheduler.class);

    private final RuntimeConfig config;
    private final PageRepository pageRepository;
    private final WorkerLoopFactory workerLoopFactory;
    private final AtomicBoolean shutdown;

    private volatile ExecutorService executor;
    private final CountDownLatch runCompleted = new CountDownLatch(1);

    public VirtualThreadCrawlerScheduler(
            RuntimeConfig config,
            PageRepository pageRepository,
            WorkerLoopFactory workerLoopFactory,
            AtomicBoolean shutdown) {
        this.config = Objects.requireNonNull(config, "config");
        this.pageRepository = Objects.requireNonNull(pageRepository, "pageRepository");
        this.workerLoopFactory = Objects.requireNonNull(workerLoopFactory, "workerLoopFactory");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
    }

    @Override
    public void start(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }
        ExecutorService exec = SchedulingPolicy.newVirtualThreadWorkerExecutor();
        this.executor = exec;
        for (int i = 0; i < workerCount; i++) {
            String workerId = SchedulingPolicy.newWorkerId();
            WorkerLoop loop = workerLoopFactory.create(workerId, shutdown);
            exec.submit(loop::runLoop);
        }
        Thread.ofVirtual()
                .name("crawler-termination-supervisor")
                .start(
                        () -> {
                            try {
                                runSupervisor(exec);
                            } finally {
                                runCompleted.countDown();
                            }
                        });
    }

    /**
     * Blocks until the supervisor finishes (natural crawl completion or external shutdown). Then waits for the
     * worker executor to terminate.
     */
    public void awaitRunCompletion(Duration timeout) throws InterruptedException {
        boolean finished = runCompleted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            throw new IllegalStateException("supervisor did not complete within " + timeout);
        }
        ExecutorService exec = executor;
        if (exec != null) {
            exec.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void runSupervisor(ExecutorService exec) {
        long pollMs = Math.max(50L, config.frontierPollMs());
        long graceMs = config.frontierTerminationGraceMs();
        long graceStart = -1L;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (shutdown.get()) {
                    break;
                }
                long pending = pageRepository.countNonTerminalQueuePages();
                long now = System.currentTimeMillis();
                if (pending > 0) {
                    graceStart = -1L;
                } else {
                    if (graceStart < 0L) {
                        graceStart = now;
                    } else if (graceMs == 0 || now - graceStart >= graceMs) {
                        log.info(
                                "crawlTermination supervisor=emptyQueueGrace pending={} graceMs={}",
                                pending,
                                graceMs);
                        shutdown.set(true);
                        break;
                    }
                }
                Thread.sleep(pollMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown.set(true);
        } finally {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(1, TimeUnit.HOURS)) {
                    log.warn("executor did not terminate within 1 hour; forcing shutdownNow");
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
    }

    @Override
    public void stopGracefully(Duration timeout) {
        shutdown.set(true);
        ExecutorService exec = executor;
        if (exec != null) {
            exec.shutdown();
            try {
                exec.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
    }
}
