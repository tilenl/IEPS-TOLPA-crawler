/*
 * TS-12 / TS-15 in-process metrics: failure categories, queue health, fetch latency histograms per domain,
 * rate-limit waits, dedup, lease recovery, headless saturation, and robots diagnostics.
 *
 * Callers: frontier claim paths, PageRepository, HttpFetcher, PolitenessGate, WorkerLoop, EnqueueService,
 * HeadlessSessionPool, RunSummaryReporter.
 *
 * Created: 2026-03. Major revision: TS-15 metric surface (bounded per-domain maps, no Micrometer yet).
 */

package si.uni_lj.fri.wier.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;

/**
 * Thread-safe counters and bounded histograms for crawler observability (TS-12, TS-15).
 *
 * <p>Per-domain fetch latency uses fixed millisecond buckets and caps the number of tracked domain keys so
 * hostile crawls cannot grow memory without bound. Domains beyond the cap roll into {@value #OVERFLOW_DOMAIN_KEY}.
 *
 * <p>Queue-health fields ({@link #delayedQueueAgeMillis()}, {@link #oldestOverdueRetryMillis()}) are updated
 * from {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#sampleFrontierOverdueHealth()} via
 * {@link si.uni_lj.fri.wier.storage.frontier.FrontierStore}.
 */
public final class CrawlerMetrics {

    /** Domains that exceed {@link #MAX_TRACKED_LATENCY_DOMAINS} aggregate here. */
    public static final String OVERFLOW_DOMAIN_KEY = "_other_";

    /** Upper bound on distinct domain keys in {@link #latencyBucketsByDomain}. */
    public static final int MAX_TRACKED_LATENCY_DOMAINS = 256;

    /**
     * Bucket upper bounds in ms: {@code [0,100), [100,500), ... , >=60000}. Length defines bucket count minus one
     * trailing open-ended bucket.
     */
    private static final long[] LATENCY_BUCKET_UPPER_MS = {
        100L, 500L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L
    };

    private static final int LATENCY_BUCKET_COUNT = LATENCY_BUCKET_UPPER_MS.length + 1;

    private final Map<CrawlerErrorCategory, AtomicLong> failuresByCategory = new EnumMap<>(CrawlerErrorCategory.class);
    private final AtomicLong retriesScheduled = new AtomicLong();
    private final AtomicLong terminalFailures = new AtomicLong();

    private final AtomicLong delayedQueueAgeMillis = new AtomicLong();
    private final AtomicLong oldestOverdueRetryMillis = new AtomicLong();
    private final AtomicLong overdueFrontierRowCount = new AtomicLong();

    private final AtomicLong frontierDepthMirror = new AtomicLong();

    private final AtomicLong rateLimitWaitEvents = new AtomicLong();
    private final AtomicLong rateLimitWaitMillisTotal = new AtomicLong();

    private final AtomicLong budgetDroppedTotal = new AtomicLong();
    private final AtomicLong frontierDeferredTotal = new AtomicLong();
    private final AtomicLong frontierEvictedForScoreTotal = new AtomicLong();
    private final AtomicLong frontierFullLowScoreTotal = new AtomicLong();

    private final AtomicLong leaseRecoveryRowsTotal = new AtomicLong();
    private final AtomicLong leaseRecoveryBatchesTotal = new AtomicLong();
    private final AtomicLong maxObservedLeaseAgeMs = new AtomicLong();

    private final AtomicLong urlDedupHits = new AtomicLong();
    private final AtomicLong contentDedupHits = new AtomicLong();

    private final AtomicLong terminalHtmlPages = new AtomicLong();
    private final AtomicLong terminalBinaryPages = new AtomicLong();

    private final AtomicLong headlessAcquireTimeouts = new AtomicLong();
    private final AtomicLong headlessCircuitOpenEvents = new AtomicLong();

    private final AtomicInteger robotsTemporaryDenyDomainsGauge = new AtomicInteger();

    private final AtomicLong dbTimeoutOrTimeoutLikeSql = new AtomicLong();

    private final ConcurrentHashMap<String, long[]> latencyBucketsByDomain = new ConcurrentHashMap<>();
    private final AtomicInteger latencyDomainCardinality = new AtomicInteger();
    private final Object latencyHistogramLock = new Object();

    /** Key: {@code domain + "|" + statusClass} where statusClass is e.g. {@code 2xx}, {@code 4xx}, {@code 3xx_5xx}. */
    private final ConcurrentHashMap<String, LongAdder> robotsFetchFailuresByKey = new ConcurrentHashMap<>();

    /** Logical JDBC checkouts seen through {@link si.uni_lj.fri.wier.storage.postgres.CountingDataSource}. */
    private final AtomicInteger dbConnectionsCheckedOut = new AtomicInteger();

    private volatile int dbPoolCapacity;
    private final AtomicInteger headlessSlotsInUse = new AtomicInteger();
    private volatile int headlessPoolCapacity;

    public CrawlerMetrics() {
        for (CrawlerErrorCategory c : CrawlerErrorCategory.values()) {
            failuresByCategory.put(c, new AtomicLong());
        }
    }

    /** Counts each classified failure entering {@link si.uni_lj.fri.wier.error.ProcessingFailureHandler}. */
    public void recordFailure(CrawlerErrorCategory category) {
        failuresByCategory.get(category).incrementAndGet();
    }

    public void recordRetryScheduled() {
        retriesScheduled.incrementAndGet();
    }

    public void recordTerminalFailure() {
        terminalFailures.incrementAndGet();
    }

    public long failureCount(CrawlerErrorCategory category) {
        return failuresByCategory.get(category).get();
    }

    public long retryScheduledCount() {
        return retriesScheduled.get();
    }

    public long terminalFailureCount() {
        return terminalFailures.get();
    }

    public void recordFrontierQueueHealth(
            long overdueFrontierCount, long avgOverdueMillis, long oldestOverdueMillis) {
        overdueFrontierRowCount.set(overdueFrontierCount);
        delayedQueueAgeMillis.set(avgOverdueMillis);
        oldestOverdueRetryMillis.set(oldestOverdueMillis);
    }

    public long overdueFrontierRowCount() {
        return overdueFrontierRowCount.get();
    }

    public long delayedQueueAgeMillis() {
        return delayedQueueAgeMillis.get();
    }

    public long oldestOverdueRetryMillis() {
        return oldestOverdueRetryMillis.get();
    }

    /** Mirrors last heartbeat / claim snapshot of {@code FRONTIER} row count (TS-15 queue depth). */
    public void setFrontierDepthMirror(long count) {
        frontierDepthMirror.set(count);
    }

    public long frontierDepthMirror() {
        return frontierDepthMirror.get();
    }

    /** Each politeness or overload wait applied to a worker (one event per delayed decision consumed). */
    public void recordRateLimitWait(long waitMillis) {
        if (waitMillis <= 0L) {
            return;
        }
        rateLimitWaitEvents.incrementAndGet();
        rateLimitWaitMillisTotal.addAndGet(waitMillis);
    }

    public long rateLimitWaitEvents() {
        return rateLimitWaitEvents.get();
    }

    public long rateLimitWaitMillisTotal() {
        return rateLimitWaitMillisTotal.get();
    }

    public void recordBudgetDropped() {
        budgetDroppedTotal.incrementAndGet();
    }

    public void recordFrontierDeferred() {
        frontierDeferredTotal.incrementAndGet();
    }

    public void recordFrontierEvictedForScore() {
        frontierEvictedForScoreTotal.incrementAndGet();
    }

    public void recordFrontierFullLowScore() {
        frontierFullLowScoreTotal.incrementAndGet();
    }

    public long budgetDroppedTotal() {
        return budgetDroppedTotal.get();
    }

    public long frontierDeferredTotal() {
        return frontierDeferredTotal.get();
    }

    public long frontierEvictedForScoreTotal() {
        return frontierEvictedForScoreTotal.get();
    }

    public long frontierFullLowScoreTotal() {
        return frontierFullLowScoreTotal.get();
    }

    /**
     * Stale-lease recovery: {@code rows} is the UPDATE row count for one batch; increments batch counter when {@code
     * rows > 0}.
     */
    public void recordLeaseRecoveryBatch(int rows) {
        if (rows <= 0) {
            return;
        }
        leaseRecoveryRowsTotal.addAndGet(rows);
        leaseRecoveryBatchesTotal.incrementAndGet();
    }

    public long leaseRecoveryRowsTotal() {
        return leaseRecoveryRowsTotal.get();
    }

    public long leaseRecoveryBatchesTotal() {
        return leaseRecoveryBatchesTotal.get();
    }

    /**
     * Updates running max lease age (ms) from DB snapshot: {@code now - claimed_at} for oldest {@code PROCESSING} row.
     */
    public void recordOldestActiveLeaseAgeSample(long ageMs) {
        if (ageMs < 0L) {
            return;
        }
        maxObservedLeaseAgeMs.accumulateAndGet(ageMs, Math::max);
    }

    public long maxObservedLeaseAgeMs() {
        return maxObservedLeaseAgeMs.get();
    }

    public void recordUrlDedupHit() {
        urlDedupHits.incrementAndGet();
    }

    public void recordContentDedupHit() {
        contentDedupHits.incrementAndGet();
    }

    public long urlDedupHits() {
        return urlDedupHits.get();
    }

    public long contentDedupHits() {
        return contentDedupHits.get();
    }

    public void recordTerminalHtmlPersisted() {
        terminalHtmlPages.incrementAndGet();
    }

    public void recordTerminalBinaryPersisted() {
        terminalBinaryPages.incrementAndGet();
    }

    public long terminalHtmlPages() {
        return terminalHtmlPages.get();
    }

    public long terminalBinaryPages() {
        return terminalBinaryPages.get();
    }

    public void recordHeadlessAcquireTimeout() {
        headlessAcquireTimeouts.incrementAndGet();
    }

    public void recordHeadlessCircuitOpened() {
        headlessCircuitOpenEvents.incrementAndGet();
    }

    public long headlessAcquireTimeouts() {
        return headlessAcquireTimeouts.get();
    }

    public long headlessCircuitOpenEvents() {
        return headlessCircuitOpenEvents.get();
    }

    public void setRobotsTemporaryDenyDomains(int count) {
        robotsTemporaryDenyDomainsGauge.set(Math.max(0, count));
    }

    public int robotsTemporaryDenyDomains() {
        return robotsTemporaryDenyDomainsGauge.get();
    }

    /**
     * Robots.txt fetch outcome for metrics (TS-15 {@code robots_fetch_failures_total} style — here we count attempts
     * with status class for operator dashboards).
     */
    public void recordRobotsFetchOutcome(String domain, int statusCode) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        String d = domain.trim().toLowerCase();
        String statusClass;
        if (statusCode >= 200 && statusCode < 300) {
            statusClass = "2xx";
        } else if (statusCode >= 400 && statusCode < 500) {
            statusClass = "4xx";
        } else if (statusCode >= 300 && statusCode < 400 || statusCode >= 500) {
            statusClass = "3xx_5xx";
        } else {
            statusClass = "other";
        }
        robotsFetchFailuresByKey.computeIfAbsent(d + "|" + statusClass, k -> new LongAdder()).increment();
    }

    /** Non-HTTP robots failures (redirect loop, parse error, etc.). */
    public void recordRobotsFetchFailure(String domain, String reasonBucket) {
        if (domain == null || domain.isBlank()) {
            return;
        }
        String d = domain.trim().toLowerCase();
        String bucket = reasonBucket == null || reasonBucket.isBlank() ? "failure" : reasonBucket;
        robotsFetchFailuresByKey.computeIfAbsent(d + "|" + bucket, k -> new LongAdder()).increment();
    }

    public Map<String, Long> robotsFetchFailureCountsSnapshot() {
        return Map.copyOf(
                robotsFetchFailuresByKey.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum())));
    }

    public void recordDbTimeoutLikeFailure() {
        dbTimeoutOrTimeoutLikeSql.incrementAndGet();
    }

    public long dbTimeoutLikeFailures() {
        return dbTimeoutOrTimeoutLikeSql.get();
    }

    /**
     * Records end-to-end fetch time for one completed hop (plain or headless). Buckets are per normalized domain key.
     */
    public void recordFetchLatencyMillis(String domainKey, long durationMs) {
        if (durationMs < 0L) {
            return;
        }
        String key = normalizeDomainKey(domainKey);
        long[] buckets = latencyBucketsForDomain(key);
        int idx = bucketIndex(durationMs);
        synchronized (buckets) {
            buckets[idx]++;
        }
    }

    private String normalizeDomainKey(String domain) {
        if (domain == null || domain.isBlank()) {
            return OVERFLOW_DOMAIN_KEY;
        }
        return domain.trim().toLowerCase();
    }

    private long[] latencyBucketsForDomain(String domainKey) {
        long[] hit = latencyBucketsByDomain.get(domainKey);
        if (hit != null) {
            return hit;
        }
        // Synchronized: choose a new per-domain bucket array or route excess domains to OVERFLOW without aliasing
        // multiple map keys to the same array (which would double-count in snapshots).
        synchronized (latencyHistogramLock) {
            hit = latencyBucketsByDomain.get(domainKey);
            if (hit != null) {
                return hit;
            }
            if (latencyDomainCardinality.get() >= MAX_TRACKED_LATENCY_DOMAINS) {
                return latencyBucketsByDomain.computeIfAbsent(
                        OVERFLOW_DOMAIN_KEY, k -> new long[LATENCY_BUCKET_COUNT]);
            }
            long[] created = new long[LATENCY_BUCKET_COUNT];
            latencyBucketsByDomain.put(domainKey, created);
            latencyDomainCardinality.incrementAndGet();
            return created;
        }
    }

    private static int bucketIndex(long durationMs) {
        for (int i = 0; i < LATENCY_BUCKET_UPPER_MS.length; i++) {
            if (durationMs < LATENCY_BUCKET_UPPER_MS[i]) {
                return i;
            }
        }
        return LATENCY_BUCKET_COUNT - 1;
    }

    /**
     * Defensive copy of latency histogram: map from domain to bucket counts (same order as internal upper bounds).
     */
    public Map<String, long[]> fetchLatencyHistogramSnapshot() {
        Map<String, long[]> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, long[]> e : latencyBucketsByDomain.entrySet()) {
            synchronized (e.getValue()) {
                out.put(e.getKey(), java.util.Arrays.copyOf(e.getValue(), LATENCY_BUCKET_COUNT));
            }
        }
        return out;
    }

    public static long[] latencyBucketUpperBoundsMs() {
        return java.util.Arrays.copyOf(LATENCY_BUCKET_UPPER_MS, LATENCY_BUCKET_UPPER_MS.length);
    }

    public static int latencyBucketCount() {
        return LATENCY_BUCKET_COUNT;
    }

    /** Configured JDBC pool ceiling ({@code crawler.db.poolSize}); used with checkout counts for utilization. */
    public void setDbPoolCapacity(int capacity) {
        this.dbPoolCapacity = Math.max(0, capacity);
    }

    public int dbPoolCapacity() {
        return dbPoolCapacity;
    }

    public void onDbConnectionCheckedOut() {
        dbConnectionsCheckedOut.incrementAndGet();
    }

    public void onDbConnectionReturned() {
        dbConnectionsCheckedOut.updateAndGet(v -> Math.max(0, v - 1));
    }

    public int dbConnectionsCheckedOut() {
        return Math.max(0, dbConnectionsCheckedOut.get());
    }

    /**
     * Permille (0–1000) of configured capacity; 0 when capacity unset. Coarse TS-15 “pool utilization” signal for
     * logs and run summary (not a JDBC driver pool gauge).
     */
    public int dbPoolUtilizationPermille() {
        int cap = dbPoolCapacity;
        if (cap <= 0) {
            return 0;
        }
        return (int) Math.min(1000L, (1000L * dbConnectionsCheckedOut()) / cap);
    }

    public void setHeadlessPoolCapacity(int maxSlots) {
        this.headlessPoolCapacity = Math.max(0, maxSlots);
    }

    public int headlessPoolCapacity() {
        return headlessPoolCapacity;
    }

    public void onHeadlessSlotAcquired() {
        headlessSlotsInUse.incrementAndGet();
    }

    public void onHeadlessSlotReleased() {
        headlessSlotsInUse.updateAndGet(v -> Math.max(0, v - 1));
    }

    public int headlessSlotsInUse() {
        return Math.max(0, headlessSlotsInUse.get());
    }

    public int headlessSlotUtilizationPermille() {
        int cap = headlessPoolCapacity;
        if (cap <= 0) {
            return 0;
        }
        return (int) Math.min(1000L, (1000L * headlessSlotsInUse()) / cap);
    }
}
