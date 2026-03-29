/*
 * Per-domain frontier dequeue pump: priority wake queue + domain-scoped claim + outer politeness before pipeline handoff.
 *
 * Callers: {@link VirtualThreadCrawlerScheduler} starts one virtual thread running {@link #runPumpLoop}. Stage A
 * notifies {@link #scheduleDomainForWork} after committed FRONTIER inserts ({@code PageRepository} hook).
 *
 * Created: 2026-03 for domain-aligned dequeue (single-domain claim thrash fix).
 */

package si.uni_lj.fri.wier.scheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;
import si.uni_lj.fri.wier.downloader.worker.WorkerLoop;
import si.uni_lj.fri.wier.scheduler.policies.SchedulingPolicy;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.error.RecoveryPathExecutor;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Serializes dequeue per crawl domain: claim only after scheduling, consume politeness token, then submit one
 * {@link WorkerLoop} pipeline task. Cross-domain work runs in parallel up to {@link Semaphore} capacity.
 */
public final class DomainFrontierPump {

    private static final Logger log = LoggerFactory.getLogger(DomainFrontierPump.class);

    private final AtomicBoolean shutdown;
    private final FrontierStore frontierStore;
    private final PolitenessGate politenessGate;
    private final WorkerLoopFactory workerLoopFactory;
    private final ExecutorService pipelineExecutor;
    private final Semaphore pipelineConcurrency;
    private final PageRepository pageRepository;
    private final String pumpWorkerId;
    private final java.time.Duration leaseDuration;
    private final int leaseRecoveryBatchSize;
    private final RecoveryPathExecutor pumpRecoveryPath;
    private final Clock clock;
    private final CrawlerMetrics observabilityMetrics;
    private final AtomicReference<String> heartbeatWorkerIdSink;

    private final Object wakeMonitor = new Object();
    private final PriorityQueue<Wakeup> wakeQueue =
            new PriorityQueue<>(Comparator.comparingLong(Wakeup::dueEpochMs));

    private final ConcurrentHashMap<String, Object> domainLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> pipelineInflight = new ConcurrentHashMap<>();

    private record Wakeup(String domain, long dueEpochMs) {}

    public DomainFrontierPump(
            AtomicBoolean shutdown,
            FrontierStore frontierStore,
            PolitenessGate politenessGate,
            WorkerLoopFactory workerLoopFactory,
            ExecutorService pipelineExecutor,
            Semaphore pipelineConcurrency,
            PageRepository pageRepository,
            String pumpWorkerId,
            java.time.Duration leaseDuration,
            int leaseRecoveryBatchSize,
            RecoveryPathExecutor pumpRecoveryPath,
            Clock clock,
            CrawlerMetrics observabilityMetrics,
            AtomicReference<String> heartbeatWorkerIdSink) {
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.frontierStore = Objects.requireNonNull(frontierStore, "frontierStore");
        this.politenessGate = Objects.requireNonNull(politenessGate, "politenessGate");
        this.workerLoopFactory = Objects.requireNonNull(workerLoopFactory, "workerLoopFactory");
        this.pipelineExecutor = Objects.requireNonNull(pipelineExecutor, "pipelineExecutor");
        this.pipelineConcurrency = Objects.requireNonNull(pipelineConcurrency, "pipelineConcurrency");
        this.pageRepository = Objects.requireNonNull(pageRepository, "pageRepository");
        this.pumpWorkerId = Objects.requireNonNull(pumpWorkerId, "pumpWorkerId");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.leaseRecoveryBatchSize = leaseRecoveryBatchSize;
        this.pumpRecoveryPath = Objects.requireNonNull(pumpRecoveryPath, "pumpRecoveryPath");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observabilityMetrics = observabilityMetrics;
        this.heartbeatWorkerIdSink = heartbeatWorkerIdSink;
    }

    /**
     * Enqueues an immediate wakeup for {@code crawlDomain} (dedupes prior pending wakes for the same domain).
     *
     * @param crawlDomain key matching {@code crawldb.site.domain}
     */
    public void scheduleDomainForWork(String crawlDomain) {
        if (crawlDomain == null || crawlDomain.isBlank()) {
            return;
        }
        scheduleDomainAtEpochMs(crawlDomain.trim(), System.currentTimeMillis());
    }

    private void scheduleDomainAtEpochMs(String crawlDomain, long dueEpochMs) {
        synchronized (wakeMonitor) {
            // Later schedule replaces an earlier pending wake for the same domain so the queue stays small.
            wakeQueue.removeIf(w -> crawlDomain.equals(w.domain));
            wakeQueue.add(new Wakeup(crawlDomain, dueEpochMs));
            wakeMonitor.notifyAll();
        }
    }

    /**
     * Schedules every domain that currently has at least one FRONTIER row (startup and safety net after registration).
     */
    public void seedDomainsWithFrontierWork() {
        for (String d : pageRepository.listDistinctFrontierCrawlDomains()) {
            scheduleDomainForWork(d);
        }
    }

    /**
     * Pump virtual-thread entry: blocks on the wake queue until {@link #shutdown}, then processes due domains.
     */
    public void runPumpLoop() {
        try {
            while (!shutdown.get()) {
                Wakeup next = takeNextDueWakeup();
                if (next == null) {
                    if (shutdown.get()) {
                        return;
                    }
                    continue;
                }
                if (shutdown.get()) {
                    return;
                }
                processDueDomain(next.domain);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("pump interrupted");
        }
    }

    private Wakeup takeNextDueWakeup() throws InterruptedException {
        synchronized (wakeMonitor) {
            while (!shutdown.get()) {
                Wakeup peek = wakeQueue.peek();
                if (peek == null) {
                    wakeMonitor.wait(500);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (peek.dueEpochMs > now) {
                    long waitMs = Math.min(peek.dueEpochMs - now + 1L, 60_000L);
                    wakeMonitor.wait(waitMs);
                    continue;
                }
                return wakeQueue.poll();
            }
            return null;
        }
    }

    private void processDueDomain(String domain) {
        Object lock = domainLocks.computeIfAbsent(domain, d -> new Object());
        FrontierRow row;
        synchronized (lock) {
            if (Boolean.TRUE.equals(pipelineInflight.get(domain))) {
                // One in-flight pipeline per domain; retry soon (another task still holds the lease chain).
                scheduleDomainAtEpochMs(domain, System.currentTimeMillis() + 25L);
                return;
            }
            // Claim first: avoids consuming a politeness token when this domain has no eligible FRONTIER row.
            Optional<FrontierRow> claimed =
                    frontierStore.claimNextEligibleFrontierForDomain(
                            pumpWorkerId, leaseDuration, domain, leaseRecoveryBatchSize);
            if (claimed.isEmpty()) {
                scheduleDomainAfterEmptyClaim(domain);
                return;
            }
            row = claimed.get();

            RateLimitDecision rl = politenessGate.tryAcquire(domain);
            if (rl.delayed()) {
                if (observabilityMetrics != null) {
                    observabilityMetrics.recordRateLimitWait(
                            Math.max(1L, TimeUnit.NANOSECONDS.toMillis(rl.waitNs())));
                }
                Instant next = clock.instant().plusNanos(rl.waitNs());
                FrontierRow r = row;
                pumpRecoveryPath.runWithRetries(
                        pumpWorkerId,
                        r.pageId(),
                        r.claimExpiresAt(),
                        CrawlerErrorCategory.FETCH_HTTP_OVERLOAD.name(),
                        () -> {
                            boolean ok =
                                    frontierStore.reschedulePage(
                                            r.pageId(),
                                            next,
                                            CrawlerErrorCategory.FETCH_HTTP_OVERLOAD.name(),
                                            "rate_limited");
                            if (!ok) {
                                throw new IllegalStateException("reschedule failed pageId=" + r.pageId());
                            }
                        });
                scheduleDomainAtEpochMs(domain, System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(rl.waitNs()));
                return;
            }

            pipelineInflight.put(domain, Boolean.TRUE);
        }

        try {
            pipelineConcurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                pipelineInflight.remove(domain);
            }
            clearInflightAndRescheduleRow(
                    row,
                    CrawlerErrorCategory.FETCH_TIMEOUT.name(),
                    "pump interrupted waiting for pipeline slot");
            scheduleDomainForWork(domain);
            return;
        }

        if (shutdown.get()) {
            pipelineConcurrency.release();
            synchronized (lock) {
                pipelineInflight.remove(domain);
            }
            clearInflightAndRescheduleRow(
                    row,
                    CrawlerErrorCategory.FETCH_TIMEOUT.name(),
                    "shutdown before pipeline start");
            return;
        }

        String pipelineWorkerId = SchedulingPolicy.newWorkerId();
        if (heartbeatWorkerIdSink != null) {
            heartbeatWorkerIdSink.compareAndSet(null, pipelineWorkerId);
        }
        WorkerLoop loop = workerLoopFactory.create(pipelineWorkerId, shutdown);
        try {
            pipelineExecutor.submit(
                    () -> {
                        try {
                            if (!shutdown.get()) {
                                loop.runClaimedPipeline(row, true);
                            }
                        } finally {
                            pipelineConcurrency.release();
                            synchronized (lock) {
                                pipelineInflight.remove(domain);
                            }
                            if (!shutdown.get()) {
                                scheduleDomainForWork(domain);
                            }
                        }
                    });
        } catch (RuntimeException e) {
            pipelineConcurrency.release();
            synchronized (lock) {
                pipelineInflight.remove(domain);
            }
            log.warn("pipeline submit failed domain={} msg={}", domain, e.getMessage());
            clearInflightAndRescheduleRow(
                    row,
                    CrawlerErrorCategory.FETCH_CAPACITY_EXHAUSTED.name(),
                    "executor rejected pipeline task: " + e.getMessage());
            scheduleDomainForWork(domain);
        }
    }

    private void scheduleDomainAfterEmptyClaim(String domain) {
        if (shutdown.get()) {
            return;
        }
        Optional<Instant> minNext = pageRepository.minNextAttemptAtForFrontierDomain(domain);
        if (minNext.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long due = Math.max(now, minNext.get().toEpochMilli());
        scheduleDomainAtEpochMs(domain, due);
    }

    private void clearInflightAndRescheduleRow(FrontierRow row, String errorCategory, String diagnostic) {
        FrontierRow r = row;
        pumpRecoveryPath.runWithRetries(
                pumpWorkerId,
                r.pageId(),
                r.claimExpiresAt(),
                errorCategory,
                () -> {
                    boolean ok =
                            frontierStore.reschedulePage(
                                    r.pageId(), clock.instant(), errorCategory, diagnostic);
                    if (!ok) {
                        throw new IllegalStateException("reschedule failed pageId=" + r.pageId());
                    }
                });
    }
}
