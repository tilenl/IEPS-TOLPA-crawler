/*
 * TS-02 / TS-14: virtual-thread executor hosting the domain frontier pump, pipeline tasks, and termination supervisor.
 *
 * Callers: {@link si.uni_lj.fri.wier.app.PreferentialCrawler}. Natural completion shuts down the executor; external
 * stop uses {@link #stopGracefully(Duration)} (e.g. JVM shutdown hook).
 *
 * Created: 2026-03. Major revision: domain-aligned dequeue pump replaces N blind {@code WorkerLoop} pollers.
 */

package si.uni_lj.fri.wier.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;
import si.uni_lj.fri.wier.error.RecoveryPathExecutor;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Runs one {@link DomainFrontierPump} virtual thread, up to {@code workerCount} concurrent pipeline tasks on the
 * same executor, and a supervisor implementing TS-02 empty-queue grace termination.
 */
public final class VirtualThreadCrawlerScheduler implements si.uni_lj.fri.wier.contracts.Scheduler {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadCrawlerScheduler.class);

    private final RuntimeConfig config;
    private final PageRepository pageRepository;
    private final FrontierStore frontierStore;
    private final PolitenessGate politenessGate;
    private final WorkerLoopFactory workerLoopFactory;
    private final AtomicBoolean shutdown;
    private final AtomicReference<String> heartbeatWorkerIdSink;
    private final AtomicReference<Consumer<String>> frontierWakeRegistration;
    private final CrawlerMetrics crawlMetrics;

    private volatile ExecutorService executor;
    private final CountDownLatch runCompleted = new CountDownLatch(1);

    public VirtualThreadCrawlerScheduler(
            RuntimeConfig config,
            PageRepository pageRepository,
            FrontierStore frontierStore,
            PolitenessGate politenessGate,
            WorkerLoopFactory workerLoopFactory,
            AtomicBoolean shutdown,
            AtomicReference<String> heartbeatWorkerIdSink,
            AtomicReference<Consumer<String>> frontierWakeRegistration,
            CrawlerMetrics crawlMetrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.pageRepository = Objects.requireNonNull(pageRepository, "pageRepository");
        this.frontierStore = Objects.requireNonNull(frontierStore, "frontierStore");
        this.politenessGate = Objects.requireNonNull(politenessGate, "politenessGate");
        this.workerLoopFactory = Objects.requireNonNull(workerLoopFactory, "workerLoopFactory");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.heartbeatWorkerIdSink = heartbeatWorkerIdSink;
        this.frontierWakeRegistration =
                frontierWakeRegistration != null
                        ? frontierWakeRegistration
                        : new AtomicReference<>();
        this.crawlMetrics = crawlMetrics;
    }

    @Override
    public void start(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }
        ExecutorService exec = SchedulingPolicy.newVirtualThreadWorkerExecutor();
        this.executor = exec;
        Semaphore pipelineConcurrency = new Semaphore(workerCount);
        String pumpWorkerId = "pump-" + SchedulingPolicy.newWorkerId();
        RecoveryPathExecutor pumpRecovery =
                new RecoveryPathExecutor(config, LoggerFactory.getLogger("recovery-pump"));
        Clock clock = Clock.systemUTC();
        DomainFrontierPump pump =
                new DomainFrontierPump(
                        shutdown,
                        frontierStore,
                        politenessGate,
                        workerLoopFactory,
                        exec,
                        pipelineConcurrency,
                        pageRepository,
                        pumpWorkerId,
                        Duration.ofSeconds(config.frontierLeaseSeconds()),
                        config.frontierLeaseRecoveryBatchSize(),
                        pumpRecovery,
                        clock,
                        crawlMetrics,
                        heartbeatWorkerIdSink);
        frontierWakeRegistration.set(pump::scheduleDomainForWork);
        pump.seedDomainsWithFrontierWork();
        exec.submit(pump::runPumpLoop);
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
