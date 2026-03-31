/*
 * Emits a single structured {@link StructuredCrawlerLog#EVENT_CRAWLER_RUN_SUMMARY} log at crawl shutdown (TS-15).
 *
 * Callers: {@link si.uni_lj.fri.wier.cli.Main} after natural completion or from the JVM shutdown hook (best-effort).
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.observability;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Aggregates DB snapshots and in-process {@link CrawlerMetrics} into one operator-facing summary line.
 */
public final class RunSummaryReporter {

    private static final Logger log = LoggerFactory.getLogger(RunSummaryReporter.class);

    /** Default breadth for “top domains by fetch count” (terminal rows per site). */
    public static final int TOP_DOMAINS_LIMIT = 10;

    private RunSummaryReporter() {}

    /**
     * Logs {@code event=CRAWLER_RUN_SUMMARY} with TS-15 run summary fields. Safe to call from a shutdown hook; DB
     * failures are logged and swallowed so exit paths stay reliable.
     *
     * @param pageRepository live repository (same datasource as the crawl)
     * @param metrics in-process counters for delays, recoveries, dedup, headless, robots
     * @param seeds seed bootstrap stats from {@link si.uni_lj.fri.wier.app.PreferentialCrawler}
     */
    public static void emitRunSummary(
            PageRepository pageRepository, CrawlerMetrics metrics, SeedBootstrapStats seeds) {
        try {
            PageRepository.RunSummaryPageTypeSnapshot types = pageRepository.queryRunSummaryPageTypeSnapshot();
            List<PageRepository.ErrorCategoryCountRow> errRows = pageRepository.queryErrorCountsByCategory();
            List<PageRepository.TopDomainRow> topDomains =
                    pageRepository.queryTopDomainsByTerminalPageCount(TOP_DOMAINS_LIMIT);
            String errSummary =
                    errRows.stream().map(r -> r.category() + "=" + r.count()).collect(Collectors.joining(","));
            String topSummary =
                    topDomains.stream()
                            .map(r -> r.domain() + ":" + r.terminalPageCount())
                            .collect(Collectors.joining(","));
            long robotsFailSum =
                    metrics.robotsFetchFailureCountsSnapshot().values().stream().mapToLong(Long::longValue).sum();
            Map<String, long[]> latSnap = metrics.fetchLatencyHistogramSnapshot();
            long fetchSamples =
                    latSnap.values().stream().mapToLong(arr -> java.util.Arrays.stream(arr).sum()).sum();
            log.info(
                    "event={} totalUrls={} html={} hub={} binary={} duplicate={} error={} frontier={} processing={}"
                            + " errorsByCategory=[{}] topDomains=[{}] rateLimitWaits={} rateLimitWaitMsTotal={}"
                            + " leaseRecoveryRows={} leaseRecoveryBatches={} maxObservedLeaseAgeMs={}"
                            + " urlDedupHits={} contentDedupHits={} terminalHtmlPersisted={} terminalHubPersisted={}"
                            + " terminalBinaryPersisted={}"
                            + " budgetDropped={} frontierDeferred={} frontierEvictedForScore={}"
                            + " frontierFullLowScore={} hubBudgetDropped={} hubBudgetLowScore={}"
                            + " hubFrontierEvictedForScore={} headlessAcquireTimeouts={}"
                            + " headlessCircuitOpenEvents={} robotsTemporaryDenyDomains={} robotsFetchEventsTotal={}"
                            + " dbTimeoutLikeFailures={} fetchLatencySamples={} dbConnectionsCheckedOut={}"
                            + " dbPoolCapacity={} dbPoolUtilizationPermille={} headlessSlotsInUse={}"
                            + " headlessPoolCapacity={} headlessUtilizationPermille={} seedsConfigured={}"
                            + " seedsInserted={} seedsRejected={} seedBootstrapSkippedNonEmpty={}",
                    StructuredCrawlerLog.EVENT_CRAWLER_RUN_SUMMARY,
                    types.totalUrls(),
                    types.htmlCount(),
                    types.hubCount(),
                    types.binaryCount(),
                    types.duplicateCount(),
                    types.errorCount(),
                    types.frontierCount(),
                    types.processingCount(),
                    errSummary,
                    topSummary,
                    metrics.rateLimitWaitEvents(),
                    metrics.rateLimitWaitMillisTotal(),
                    metrics.leaseRecoveryRowsTotal(),
                    metrics.leaseRecoveryBatchesTotal(),
                    metrics.maxObservedLeaseAgeMs(),
                    metrics.urlDedupHits(),
                    metrics.contentDedupHits(),
                    metrics.terminalHtmlPages(),
                    metrics.terminalHubPages(),
                    metrics.terminalBinaryPages(),
                    metrics.budgetDroppedTotal(),
                    metrics.frontierDeferredTotal(),
                    metrics.frontierEvictedForScoreTotal(),
                    metrics.frontierFullLowScoreTotal(),
                    metrics.hubBudgetDroppedTotal(),
                    metrics.hubBudgetLowScoreTotal(),
                    metrics.hubFrontierEvictedForScoreTotal(),
                    metrics.headlessAcquireTimeouts(),
                    metrics.headlessCircuitOpenEvents(),
                    metrics.robotsTemporaryDenyDomains(),
                    robotsFailSum,
                    metrics.dbTimeoutLikeFailures(),
                    fetchSamples,
                    metrics.dbConnectionsCheckedOut(),
                    metrics.dbPoolCapacity(),
                    metrics.dbPoolUtilizationPermille(),
                    metrics.headlessSlotsInUse(),
                    metrics.headlessPoolCapacity(),
                    metrics.headlessSlotUtilizationPermille(),
                    seeds.configuredNonEmpty(),
                    seeds.inserted(),
                    seeds.rejected(),
                    seeds.skippedNonEmptyTable());
        } catch (Exception e) {
            log.warn(
                    "event={} result=FAILED message={}",
                    StructuredCrawlerLog.EVENT_CRAWLER_RUN_SUMMARY,
                    e.getMessage(),
                    e);
        }
    }
}
